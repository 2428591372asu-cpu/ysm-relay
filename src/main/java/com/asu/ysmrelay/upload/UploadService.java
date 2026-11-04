package com.asu.ysmrelay.upload;

import com.asu.ysmrelay.admin.AuthAdmin;
import com.asu.ysmrelay.config.RelayConfig;
import com.asu.ysmrelay.net.RelayNetwork;
import com.asu.ysmrelay.net.msg.ProbeReply;
import com.asu.ysmrelay.net.msg.UploadChunk;
import com.asu.ysmrelay.net.msg.UploadProbe;
import com.asu.ysmrelay.net.msg.UploadProgress;
import com.asu.ysmrelay.net.msg.UploadResult;
import com.asu.ysmrelay.ysm.YsmBridge;

import com.elfmcys.ysm.format.parser.CapturedModel;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.RawCompileResult;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.model.domain.Hash256;

import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 服务端上传总控。
 *
 * <p>三段式：
 * <ol>
 *   <li><b>探测</b>（{@link #onProbe}）：先判重、判上限、判权限。这一步不接收任何数据字节，
 *       所以「重复上传」和「超大文件」都只花一个包的成本。</li>
 *   <li><b>接收</b>（{@link #onChunk}）：直接追加写 staging 文件，不在内存里拼。</li>
 *   <li><b>导入</b>（{@link #importAsync}）：走 YSM 自己的 {@link RawModelImporter}，先
 *       {@code capture} 拿到模型标识并比对声明值，再 {@code convert} 出正式的 {@code .mxc}。</li>
 * </ol>
 *
 * <p>导入完成后的「广播给所有玩家」不在这里做：YSM 的 {@code ServerModelService} 自己订阅了
 * 目录变更，重扫一结束就会把新的目录快照推给在线玩家。我们只负责让目录变，以及把重扫请求
 * 做 500 ms 合并，避免十个人同时上传就扫十遍。
 */
public final class UploadService {
    private static final Logger LOGGER = LoggerFactory.getLogger("ysm_relay");
    private static final UploadService INSTANCE = new UploadService();

    /** YSM 能认的模型源扩展名。目录型模型在协议里不支持，先只走文件。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".mxc", ".ysm", ".zip", ".7z");
    private static final long RELOAD_MERGE_WINDOW_MS = 500L;
    private static final long PROGRESS_REPORT_BYTES = 1L * 1024L * 1024L;
    /** 上行速率观测窗口长度与告警倍数，见 {@link #meterRate}。 */
    private static final long RATE_WARN_WINDOW_MS = 2000L;
    private static final double RATE_WARN_FACTOR = 4.0;

    public static UploadService instance() {
        return INSTANCE;
    }

    private final Map<UUID, ServerUpload> byPlayer = new ConcurrentHashMap<>();
    private final Map<Long, ServerUpload> byTransfer = new ConcurrentHashMap<>();
    /**
     * 已落盘、但还没被目录重扫收进去的模型 → 容器文件。
     *
     * <p>两个作用，缺一个都不行：
     * <ol>
     *   <li>探测阶段判重：目录重扫被 500ms 合并，这段时间里 catalog 看不到刚落盘的文件，
     *       只查 catalog 会放行同一份模型的第二次上传。</li>
     *   <li>重扫之后核对：YSM 用 modelId 作目录主键，重复内容只留先到的；这一轮没赢的文件
     *       会成为永远不会被任何人引用的死文件，必须删掉。</li>
     * </ol>
     */
    private final Map<Hash256, Path> pendingCommits = new ConcurrentHashMap<>();
    private final AtomicLong nextTransferId = new AtomicLong(1L);

    /**
     * 模型转换线程池：独立、有界、**不与 YSM 共池**。
     *
     * <p>共池的症状是服主 {@code /ysm model reload} 莫名变慢，而且从日志上归不了因 ——
     * YSM 的 {@code ThreadCount} 管的是它自己的模型处理并发，我们把同步 IO 塞进它的队列，
     * 对方的线程数就变成了我们的负载。
     *
     * <p>延迟创建：容量取自 {@code maxConcurrentConversions}。这个值在运行期改了要重启才生效
     * —— 重建线程池会让正在转换的任务失去归属，比多等一次重启贵得多。
     */
    private volatile ExecutorService conversions;

    /**
     * 探测阶段的「检查 → 占位 → 落盘」是一段临界区。
     *
     * <p>不靠「probe 在主线程」当证明：500 ms 合并重扫与转换线程池都在旁边跑，
     * 而且 {@code upload.open()} 里是真实 IO，检查与写入之间隔着几次文件系统调用。
     * 两支并发 probe 同时通过「这个人没有未完成上传」的检查，后者会覆盖前者的引用，
     * 前一个暂存文件从此没有任何人持有，只能等 TTL 兜。
     */
    private final Object probeLock = new Object();

    /** 上行速率的观测窗口，只用于告警归因，不参与准入。见 {@link #meterRate}。 */
    private final Map<Long, RateWindow> rateWindows = new ConcurrentHashMap<>();

    private final ScheduledExecutorService reloader = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "ysm-relay-reload");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean reloadScheduled = new AtomicBoolean();
    private final AtomicLong reloadDueAt = new AtomicLong();
    private final AtomicBoolean stagingSwept = new AtomicBoolean();

    private UploadService() {}

    // ------------------------------------------------------------------
    // 1. 探测
    // ------------------------------------------------------------------

    public void onProbe(ServerPlayer player, UploadProbe probe) {
        sweepStagingOnce();

        if (!RelayConfig.ALLOW_PLAYER_UPLOAD.get()) {
            reply(player, ProbeReply.denied(UploadResult.REJECTED_POLICY, "本服没有开启玩家模型上传"));
            return;
        }
        var requiredLevel = RelayConfig.UPLOAD_PERMISSION_LEVEL.get();
        if (requiredLevel > 0 && !player.hasPermissions(requiredLevel)) {
            reply(player, ProbeReply.denied(UploadResult.REJECTED_POLICY, "你没有上传模型的权限"));
            return;
        }
        if (probe.size <= 0L) {
            reply(player, ProbeReply.denied(UploadResult.REJECTED_IDENTITY, "文件为空"));
            return;
        }
        var limit = RelayConfig.maxModelBytes();
        if (limit > 0L && probe.size > limit) {
            reply(player, ProbeReply.denied(UploadResult.REJECTED_QUOTA,
                "模型超过上限 " + (limit / (1024L * 1024L)) + " MiB"));
            return;
        }

        // 配额按 UploadRegistry 里记录的上传量算，不遍历磁盘：服主手动放进目录的模型不占玩家配额。
        var perPlayer = RelayConfig.maxBytesPerPlayer();
        if (perPlayer > 0L) {
            var used = UploadRegistry.get().bytesBy(player.getUUID().toString());
            if (used + probe.size > perPlayer) {
                reply(player, ProbeReply.denied(UploadResult.REJECTED_QUOTA, "超出个人配额：已用 " + mib(used)
                    + "，本次 " + mib(probe.size) + "，上限 " + mib(perPlayer)));
                return;
            }
        }
        var total = RelayConfig.maxTotalBytes();
        if (total > 0L) {
            long used = 0L;
            for (var entry : UploadRegistry.get().all()) {
                used += entry.size();
            }
            if (used + probe.size > total) {
                reply(player, ProbeReply.denied(UploadResult.REJECTED_QUOTA,
                    "服务端上传总量已满：已用 " + mib(used) + " / " + mib(total)));
                return;
            }
        }
        var extension = extensionOf(probe.fileName);
        if (extension == null) {
            reply(player, ProbeReply.denied(UploadResult.REJECTED_IDENTITY, "不支持的文件类型，只收 " + ALLOWED_EXTENSIONS));
            return;
        }
        if (!YsmBridge.serverReady()) {
            reply(player, ProbeReply.denied(UploadResult.REJECTED_POLICY, "服务端模型服务还没起来"));
            return;
        }

        Hash256 declared;
        try {
            declared = new Hash256(probe.blake3);
        } catch (RuntimeException invalid) {
            reply(player, ProbeReply.denied(UploadResult.REJECTED_IDENTITY, "模型标识长度不正确"));
            return;
        }

        // 服主删过的模型会进禁止上传名单。不拦这一步的话，下一次探测会把被删的模型原样收回来，
        // 删除等于没删 —— 这是管理面和上传通道之间必须有的那条线。
        if (AuthAdmin.get().isBlocked(declared)) {
            reply(player, ProbeReply.denied(UploadResult.REJECTED_POLICY, "这个模型已被服主删除并禁止上传"));
            return;
        }

        // 精确判重：catalog 的键就是模型标识，命中说明磁盘上已经有同一份内容。
        if (YsmBridge.serverModelIds().contains(declared)) {
            reply(player, ProbeReply.denied(UploadResult.ALREADY_AVAILABLE, "服务端已有相同内容的模型"));
            return;
        }
        // 已落盘但还没进目录的那一份也要拦，否则 500ms 重扫窗口内重传会落两个文件。
        if (pendingCommits.containsKey(declared)) {
            reply(player, ProbeReply.denied(UploadResult.ALREADY_AVAILABLE, "同一个模型正在入库，请稍后再试"));
            return;
        }
        // 第四处判重：内容是「待服主审核」状态时，catalog 里没有它、pendingCommits 也已经清空，
        // 前两处一起漏。少了这一处，同一份内容会被第二个人完整重传一遍，服主要审两遍，
        // 批准其中一个时另一个成为孤儿文件。
        if (PendingIndex.current().contains(declared)) {
            reply(player, ProbeReply.denied(UploadResult.ALREADY_AVAILABLE, "同一内容的模型正在等待服主审核"));
            return;
        }
        // 待审队列满：probe 阶段就拒，客户端一个字节都不用传。
        // autoApprove 打开时队列恒空（size 恒 0），这条不会误伤。
        var pendingMax = RelayConfig.maxPendingCount();
        if (pendingMax > 0 && PendingIndex.current().size() >= pendingMax) {
            reply(player, ProbeReply.denied(UploadResult.REJECTED_QUEUE_FULL,
                "待审核的模型已经攒到上限 " + pendingMax + " 个，请等服主处理"));
            return;
        }
        // 磁盘剩余下限：必须在创建任何暂存文件之前判 —— 先 open() 再判的话磁盘上会先多一个空文件，
        // 而且「拒收且暂存目录零文件」这条验收就做不到了。
        var freeFloor = RelayConfig.minFreeDiskBytes();
        if (freeFloor > 0L) {
            var staging = RelayPaths.stagingRoot();
            try {
                Files.createDirectories(staging);
                var free = Files.getFileStore(staging).getUsableSpace();
                if (free < freeFloor) {
                    reply(player, ProbeReply.denied(UploadResult.REJECTED_QUOTA,
                        "服务端磁盘剩余 " + mib(free) + "，低于保留下限 " + mib(freeFloor) + "，暂时不收新模型"));
                    return;
                }
            } catch (IOException failure) {
                LOGGER.warn("[ysm-relay] 无法读取暂存目录所在磁盘的剩余空间 {}", staging, failure);
                reply(player, ProbeReply.denied(UploadResult.REJECTED_POLICY, "服务端无法确认磁盘剩余空间"));
                return;
            }
        }
        // 暂存区总量：传输中残片与待审内容吃的是同一块磁盘，所以按两者之和判。
        // 台账（UploadRegistry）只记已入库的，看不到这两笔，必须直接看目录。
        var stagingCap = RelayConfig.maxStagingBytes();
        if (stagingCap > 0L) {
            var used = stagingUsage();
            if (used + probe.size > stagingCap) {
                reply(player, ProbeReply.denied(UploadResult.REJECTED_QUOTA,
                    "服务端暂存区已用 " + mib(used) + " / " + mib(stagingCap) + "，本次需要 " + mib(probe.size)));
                return;
            }
        }

        // 从这里开始是检查与占位，必须原子：见 probeLock 的注释。
        // 前面那些守卫放在锁外，是为了让临界区里只剩「一次 IO + 两个 put」。
        synchronized (probeLock) {
            if (byPlayer.containsKey(player.getUUID())) {
                reply(player, ProbeReply.denied(UploadResult.REJECTED_BUSY, "你已有一次上传正在进行"));
                return;
            }

            var transferId = nextTransferId.getAndIncrement();
            var stagingFile = RelayPaths.stagingRoot().resolve(transferId + extension);
            var upload = new ServerUpload(player.getUUID(), transferId, probe.blake3, probe.fileName, stagingFile,
                probe.size);
            try {
                upload.open();
            } catch (IOException failure) {
                LOGGER.warn("[ysm-relay] 无法创建暂存文件 {}", stagingFile, failure);
                reply(player, ProbeReply.denied(UploadResult.REJECTED_POLICY, "服务端无法写入暂存目录"));
                return;
            }

            byPlayer.put(player.getUUID(), upload);
            byTransfer.put(transferId, upload);
            // 三参版把服主配的上行速率一并下发；客户端据此让出，服务端只做超速观测。
            reply(player, ProbeReply.allowed(transferId, RelayConfig.CHUNK_BYTES.get(),
                RelayConfig.maxUploadBytesPerSecond()));
        }
    }

    // ------------------------------------------------------------------
    // 2. 接收
    // ------------------------------------------------------------------

    public void onChunk(ServerPlayer player, UploadChunk chunk) {
        var upload = byTransfer.get(chunk.transferId);
        if (upload == null || !upload.playerId.equals(player.getUUID())) {
            return;
        }
        meterRate(upload, chunk.data == null ? 0 : chunk.data.length);
        try {
            upload.append(chunk);
        } catch (IOException failure) {
            abort(player, upload, UploadResult.FAILED_CONVERSION, "上传中断：" + failure.getMessage());
            return;
        }

        if (!upload.isSealed()) {
            if (upload.received() >= PROGRESS_REPORT_BYTES && upload.received() % PROGRESS_REPORT_BYTES == 0L) {
                reply(player, new UploadProgress(upload.transferId, upload.received(), upload.expectedSize));
            }
            return;
        }
        if (!upload.isComplete()) {
            abort(player, upload, UploadResult.REJECTED_IDENTITY,
                "收到的字节数与声明不符（" + upload.received() + " / " + upload.expectedSize + "）");
            return;
        }
        byPlayer.remove(upload.playerId);
        byTransfer.remove(upload.transferId);
        rateWindows.remove(upload.transferId);
        conversions().submit(() -> importAsync(player, upload));
    }

    public void onDisconnect(ServerPlayer player) {
        var upload = byPlayer.remove(player.getUUID());
        if (upload == null) {
            return;
        }
        byTransfer.remove(upload.transferId);
        rateWindows.remove(upload.transferId);
        upload.discard();
        LOGGER.info("[ysm-relay] 玩家 {} 断线，放弃上传 {}", player.getGameProfile().getName(), upload.transferId);
    }

    private void abort(ServerPlayer player, ServerUpload upload, int status, String message) {
        byPlayer.remove(upload.playerId);
        byTransfer.remove(upload.transferId);
        rateWindows.remove(upload.transferId);
        upload.discard();
        reply(player, new UploadResult(upload.transferId, status, null, "", message));
    }

    // ------------------------------------------------------------------
    // 3. 导入
    // ------------------------------------------------------------------

    private void importAsync(ServerPlayer player, ServerUpload upload) {
        try {
            var target = RelayConfig.targetAuthRoot() ? RelayPaths.authRoot() : RelayPaths.uploadRoot();
            Files.createDirectories(target);
            var declared = new Hash256(upload.declaredModelId);
            var importer = new RawModelImporter(DefaultAnimationFilter.keepAll());

            RawCompileResult compiled;
            try (CapturedModel captured = importer.capture(upload.stagingFile)) {
                if (!captured.modelId().equals(declared)) {
                    complete(player, upload, UploadResult.REJECTED_IDENTITY, null, "",
                        "内容与声明的模型标识不符，可能文件已损坏或被改动");
                    return;
                }
                compiled = importer.convert(captured, target);
            }

            if (!compiled.modelHash().equals(declared)) {
                deleteQuietly(compiled.stagedContainer());
                complete(player, upload, UploadResult.REJECTED_IDENTITY, null, "", "编译后的模型标识与声明不一致");
                return;
            }

            // 判重要做三处，缺一处就会在磁盘上留死文件：
            //   1) 探测阶段查 catalog（这时还一个数据字节都没传）
            //   2) 这里再查 catalog（接住「别人刚 reload 完」的窗口）
            //   3) 查 pendingCommits（接住「我们刚写完，目录还没重扫」的窗口）
            // 只做 1+2 会漏掉 3：同一份模型在 500ms 合并窗口内被传两次时两次都写文件，
            // YSM 的 CatalogReconciler.selectFirstValid 用 putIfAbsent 去重，
            // 输的那份只产生一条扫描错误，文件永远不被引用、也不会被删。
            submit(player, upload, declared, compiled);
        } catch (Throwable failure) {
            LOGGER.warn("[ysm-relay] 导入上传内容失败 transfer={}", upload.transferId, failure);
            complete(player, upload, UploadResult.FAILED_CONVERSION, null, "", String.valueOf(failure.getMessage()));
        } finally {
            upload.discard();
        }
    }

    /**
     * <b>单一提交出口 —— W3 唯一要替换的方法体。</b>
     *
     * <p>约定（PLAN §10 J3）：探测侧的守卫全在 {@link #onProbe}，提交侧的分叉只在这里。
     * 两个模块各改一段，{@code UploadService} 的写入者始终只有一个。
     *
     * <p>W3 要在这里做的是：读 {@code RelayConfig.autoApprove()} 与 {@code autoApproveMaxBytes()}，
     * <ul>
     *   <li><b>免审</b>（autoApprove 且声明体积不超门槛）→ 调 {@link #commit}，与现在完全一致；</li>
     *   <li><b>要审</b> → 把 {@code compiled.stagedContainer()} 迁进 {@code relay-pending/<reviewId>/}、
     *       写进 {@code PendingIndex}（{@link PendingIndex#install}）、回
     *       {@code PENDING_REVIEW}，<b>不调 {@link #commit}</b>。</li>
     * </ul>
     *
     * <p>现状（W2 交付的骨架）= 无条件直接提交，等价于「永远免审」。这不是占位符糊弄，
     * 是为了让 W2 合入时上传通道的行为与合入前完全一致，不引入行为变化。
     */
    void submit(ServerPlayer player, ServerUpload upload, Hash256 modelId, RawCompileResult compiled) {
        commit(player, upload, modelId, compiled);
    }

    /** 真正落账：判重、记台账、触发重扫、给上传者补授权、回执。W3 的免审分支也走这里。 */
    private void commit(ServerPlayer player, ServerUpload upload, Hash256 declared, RawCompileResult compiled) {
        if (YsmBridge.serverModelIds().contains(declared) || pendingCommits.containsKey(declared)) {
            deleteQuietly(compiled.stagedContainer());
            complete(player, upload, UploadResult.ALREADY_AVAILABLE, declared.bytes(), "",
                "服务端已有相同内容的模型");
            return;
        }
        pendingCommits.put(declared, compiled.stagedContainer());

        requestReload();
        UploadRegistry.get().record(declared, player, upload.originalName, upload.expectedSize);
        grantToUploader(player, declared);
        complete(player, upload, UploadResult.COMMITTED, declared.bytes(),
            compiled.stagedContainer().getFileName().toString(), "");
        LOGGER.info("[ysm-relay] 玩家 {} 上传模型成功 id={} file={}",
            player.getGameProfile().getName(), declared, compiled.stagedContainer().getFileName());
    }

    private void complete(ServerPlayer player, ServerUpload upload, int status, byte[] modelId, String location,
        String message) {
        var result = new UploadResult(upload.transferId, status, modelId, location, message);
        var server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            try {
                RelayNetwork.toPlayer(player, result);
            } catch (RuntimeException failure) {
                LOGGER.debug("[ysm-relay] 上传结果发送失败（玩家可能已断开）", failure);
            }
        });
    }

    /**
     * 落 auth 根时，上传者自己必须先拿到授权。
     *
     * <p>否则会出现「传完自己都用不了」：auth 根的模型对谁都不可见，必须靠 grants 白名单。
     * 这条是「上传即私有」路径的必要收尾，漏了会让私有上传功能看起来像坏的。
     *
     * <p>授权要动玩家 capability，必须回服务器线程做。
     */
    private static void grantToUploader(ServerPlayer player, Hash256 modelId) {
        if (!RelayConfig.targetAuthRoot()) {
            return;
        }
        var server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            try {
                YsmBridge.grant(player, java.util.List.of(modelId));
            } catch (RuntimeException failure) {
                LOGGER.warn("[ysm-relay] 给上传者 {} 补授权失败 id={}", player.getGameProfile().getName(), modelId,
                    failure);
            }
        });
    }

    // ------------------------------------------------------------------
    // 重扫合并 + 暂存清理
    // ------------------------------------------------------------------

    private void requestReload() {
        var due = System.currentTimeMillis() + RELOAD_MERGE_WINDOW_MS;
        reloadDueAt.updateAndGet(previous -> Math.max(previous, due));
        if (reloadScheduled.compareAndSet(false, true)) {
            reloader.schedule(this::runReload, RELOAD_MERGE_WINDOW_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void runReload() {
        // 先快照：这一轮重扫一定看得见快照之前落盘的每一个文件，用它来判定输赢。
        var verifying = Map.copyOf(pendingCommits);
        try {
            while (true) {
                var wait = reloadDueAt.get() - System.currentTimeMillis();
                if (wait <= 0L) {
                    break;
                }
                Thread.sleep(Math.min(wait, 100L));
            }
            var outcome = YsmBridge.reloadServerCatalog().join();
            LOGGER.info("[ysm-relay] 目录重扫完成 success={} models={} errors={}",
                outcome.success(), outcome.modelCount(), outcome.errorCount());
            verifyCommits(verifying);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable failure) {
            LOGGER.warn("[ysm-relay] 目录重扫失败，本轮提交改为下一轮核对", failure);
        } finally {
            reloadScheduled.set(false);
            // 这一轮重扫之后才落盘的提交没被快照覆盖，补一轮。
            if (!pendingCommits.isEmpty()) {
                requestReload();
            }
        }
    }

    /**
     * 重扫完成后核对每个刚落盘的文件是不是真的进了目录。
     *
     * <p>反查询问的是 YSM 自己算出来的 modelId（{@code modelIdOfFile} 按容器文件匹配），
     * 不是我们上传时声明的值 —— 服务端只信自己重扫的结果。
     *
     * <p>查不到 = 同内容的更早一份已经在服上，这一份是死文件：删掉，让磁盘占用与目录一致。
     */
    private void verifyCommits(Map<Hash256, Path> verifying) {
        for (var entry : verifying.entrySet()) {
            var modelId = entry.getKey();
            var file = entry.getValue();
            if (!pendingCommits.remove(modelId, file)) {
                continue;
            }
            var committed = YsmBridge.modelIdOfFile(file).filter(modelId::equals).isPresent();
            if (committed) {
                LOGGER.info("[ysm-relay] 入库确认 id={} file={}", modelId, file.getFileName());
                continue;
            }
            deleteQuietly(file);
            UploadRegistry.get().remove(modelId);
            LOGGER.warn("[ysm-relay] 模型没进目录（同内容更早的一份已在服上），已删除多余文件 id={} file={}",
                modelId, file.getFileName());
        }
    }

    /** 启动后第一次上传时清一次超期残片，不额外挂 tick 钩子。 */
    private void sweepStagingOnce() {
        if (!stagingSwept.compareAndSet(false, true)) {
            return;
        }
        var root = RelayPaths.stagingRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        var ttl = RelayConfig.STAGING_TTL_SECONDS.get() * 1000L;
        var deadline = System.currentTimeMillis() - ttl;
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                try {
                    if (Files.isRegularFile(entry) && Files.getLastModifiedTime(entry).toMillis() < deadline) {
                        Files.deleteIfExists(entry);
                    }
                } catch (IOException ignored) {
                    // 单个文件删不掉不影响上传
                }
            }
        } catch (IOException failure) {
            LOGGER.debug("[ysm-relay] 清理暂存目录失败", failure);
        }
    }

    // ------------------------------------------------------------------
    // 线程池、暂存统计、上行速率观测
    // ------------------------------------------------------------------

    /** 转换线程池；第一次用到时按配置容量建一次。 */
    private ExecutorService conversions() {
        var running = conversions;
        if (running != null) {
            return running;
        }
        // 用 this 而不是 probeLock：建池与探测的原子性无关，混用一把锁会让「探测正在 open()」
        // 挡住所有分片的转换派发，而它们本该互不相干。
        synchronized (this) {
            if (conversions == null) {
                var size = Math.max(1, RelayConfig.maxConcurrentConversions());
                conversions = Executors.newFixedThreadPool(size, task -> {
                    var thread = new Thread(task, "ysm-relay-convert");
                    thread.setDaemon(true);
                    return thread;
                });
                LOGGER.info("[ysm-relay] 转换线程池就绪，并发 {}（独立池，不与 YSM 的 ThreadCount 共享）", size);
            }
            return conversions;
        }
    }

    /** 暂存区当前占用：传输中残片 + 待审内容，两者吃的是同一块磁盘。 */
    private static long stagingUsage() {
        return directorySize(RelayPaths.stagingRoot()) + directorySize(RelayPaths.pendingRoot());
    }

    /** 目录下所有普通文件的大小之和；目录不存在按 0 算。 */
    private static long directorySize(Path root) {
        if (!Files.isDirectory(root)) {
            return 0L;
        }
        var total = new long[1];
        try (var files = Files.walk(root)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                try {
                    total[0] += Files.size(path);
                } catch (IOException ignored) {
                    // 单个文件量不到就不计：上限是保护性检查，不该因为一个坏文件把全服堵死
                }
            });
        } catch (IOException ignored) {
            // 同上：统计不出来按 0 算，宁可放过一次也不误拒全服
        }
        return total[0];
    }

    /**
     * 上行速率的观测账本：<b>只告警，不 abort、不丢包</b>。
     *
     * <p>为什么不禁掉超速的传输，三条都是硬的：
     * <ol>
     *   <li>{@link ServerUpload} 的序号状态机靠连续分片，丢一片整条 transfer 作废 ——
     *       想「降速」就没有合法的做法，只有放行或中断两选。</li>
     *   <li>真慢网或服务端卡顿时，客户端会在一瞬间补发一批缓冲分片，瞬时速率必然越线。
     *       按速率中断会误杀老老实实的玩家，而这类误杀极难复现、极难归因。</li>
     *   <li>正常限速已经在客户端做了（探测阶段 ALLOWED 里下发的速率），
     *       服务端这一层只是给服主留一条「谁在无视限速」的线索。</li>
     * </ol>
     *
     * <p>真正的硬约束是 {@code maxModelBytes} 与 {@code maxStagingBytes}：前者封顶单次，
     * 后者封顶全服暂存占用。改包客户端最多把暂存区撑到上限就被拒，撑不爆磁盘。
     */
    private void meterRate(ServerUpload upload, int bytes) {
        var cap = RelayConfig.maxUploadBytesPerSecond();
        if (cap <= 0 || bytes <= 0) {
            return;
        }
        var window = rateWindows.computeIfAbsent(upload.transferId, id -> new RateWindow());
        window.bytes += bytes;
        var now = System.currentTimeMillis();
        var elapsed = now - window.since;
        if (elapsed < RATE_WARN_WINDOW_MS) {
            return;
        }
        if (!window.warned) {
            var observed = window.bytes * 1000.0 / (double) elapsed;
            if (observed > cap * RATE_WARN_FACTOR) {
                window.warned = true;
                LOGGER.warn("[ysm-relay] transfer={} 实测上行 {}/s，超过配置 {} 的 {} 倍；"
                        + "不中断（丢片会让整条传输作废），仅记录以便归因",
                    upload.transferId, mib((long) observed), mib(cap), RATE_WARN_FACTOR);
            }
        }
        window.reset(now);
    }

    /** 一条传输的速率窗口。只在 {@link #meterRate} 里用。 */
    private static final class RateWindow {
        private long bytes;
        private long since = System.currentTimeMillis();
        private boolean warned;

        private void reset(long now) {
            bytes = 0L;
            since = now;
        }
    }

    // ------------------------------------------------------------------

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 残留让 TTL 和 reload 的错误报告去兜
        }
    }

    /** 把字节数格式化成给人看的 MiB。只用于提示文本，不参与任何配额判定。 */
    private static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private static String extensionOf(String fileName) {
        var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        var dot = name.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        var extension = name.substring(dot);
        return ALLOWED_EXTENSIONS.contains(extension) ? extension : null;
    }

    private static void reply(ServerPlayer player, Object message) {
        try {
            RelayNetwork.toPlayer(player, message);
        } catch (RuntimeException failure) {
            LOGGER.debug("[ysm-relay] 发送回执失败", failure);
        }
    }
}
