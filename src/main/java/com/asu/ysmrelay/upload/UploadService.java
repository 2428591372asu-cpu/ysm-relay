package com.asu.ysmrelay.upload;

import com.asu.ysmrelay.admin.AuthAdmin;
import com.asu.ysmrelay.config.RelayConfig;
import com.asu.ysmrelay.config.RelayPermissions;
import com.asu.ysmrelay.net.RelayNetwork;
import com.asu.ysmrelay.net.msg.ProbeReply;
import com.asu.ysmrelay.net.msg.UploadChunk;
import com.asu.ysmrelay.net.msg.UploadProbe;
import com.asu.ysmrelay.net.msg.UploadProgress;
import com.asu.ysmrelay.net.msg.UploadResult;
import com.asu.ysmrelay.ysm.YsmBridge;

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
 *   <li><b>导入</b>（{@link #importAsync}）：把收到的文件原样放进模型目录，由 YSM 自己的
 *       导入管线转码与建索引。成品容器（YSGP2）例外：它会被当作「直放容器」，必须先验收
 *       内嵌预览，否则 YSM 会拒收。</li>
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

        // 最高优先级：「拒绝一切上传」压过其它所有开关。
        if (RelayConfig.rejectAllUploads()) {
            reply(player, ProbeReply.denied(UploadResult.REJECTED_POLICY, "本服已关闭模型上传"));
            return;
        }
        // 拉黑名单：只拒收这些人之后上传的模型，已经入库的不动。
        var blocked = RelayConfig.blockedUploaders();
        if (!blocked.isEmpty() && blocked.contains(RelayPermissions.uuidOf(player))) {
            reply(player, ProbeReply.denied(UploadResult.REJECTED_POLICY, "你已被本服禁止上传模型"));
            return;
        }
        if (!RelayConfig.ALLOW_PLAYER_UPLOAD.get()) {
            reply(player, ProbeReply.denied(UploadResult.REJECTED_POLICY, "本服没有开启玩家模型上传"));
            return;
        }
        // 0 级 = 被管理员禁用，任何上传路径都不放行（白名单也不行）。
        if (RelayPermissions.effectiveLevel(player) <= 0) {
            reply(player, ProbeReply.denied(UploadResult.REJECTED_POLICY, "你已被管理员禁用 relay 功能"));
            return;
        }
        // 白名单玩家：免审核、免个人配额、免权限等级；资源类守卫（总闸、拉黑、体积、总量）照样成立。
        var whitelisted = RelayPermissions.isUploadWhitelisted(player);
        var requiredLevel = RelayConfig.UPLOAD_PERMISSION_LEVEL.get();
        // 用模组三级，不再混用原版 op 等级。
        if (!whitelisted && requiredLevel > 0
            && RelayPermissions.effectiveLevel(player) < requiredLevel) {
            reply(player, ProbeReply.denied(UploadResult.REJECTED_POLICY, "你没有上传模型的权限"));
            return;
        }
        if (probe.size <= 0L) {
            reply(player, ProbeReply.denied(UploadResult.REJECTED_IDENTITY, "文件为空"));
            return;
        }
        // 全自动模式下，超过「自动同意上限」的文件直接拒收，不浪费带宽。
        if (RelayConfig.uploadMode().equals("full")) {
            var autoCap = RelayConfig.autoApproveMaxBytes();
            if (autoCap > 0L && probe.size > autoCap) {
                reply(player, ProbeReply.denied(UploadResult.REJECTED_QUOTA,
                    "超过自动模式上限 " + mib(autoCap) + "，本服当前是全自动模式，请改用审批模式或让服主调整"));
                return;
            }
        }
        var limit = RelayConfig.maxModelBytes();
        if (limit > 0L && probe.size > limit) {
            reply(player, ProbeReply.denied(UploadResult.REJECTED_QUOTA,
                "模型超过上限 " + (limit / (1024L * 1024L)) + " MB"));
            return;
        }

        // 配额按 UploadRegistry 里记录的上传量算，不遍历磁盘：服主手动放进目录的模型不占玩家配额。
        var perPlayer = RelayConfig.maxBytesPerPlayer();
        if (!whitelisted && perPlayer > 0L) {
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
        // 审批模式下每人的待审上限：超过就不许再传，直到服主处理掉前面那几个。
        if (!whitelisted) {
            var perPlayerPending = RelayConfig.maxPendingPerPlayer();
            if (perPlayerPending > 0) {
                var mine = PendingQueue.get().countByUploader(RelayPermissions.uuidOf(player));
                if (mine >= perPlayerPending) {
                    reply(player, ProbeReply.denied(UploadResult.REJECTED_QUEUE_FULL,
                        "你已有 " + mine + " 个模型在等待审核（上限 " + perPlayerPending + " 个），请等服主处理"));
                    return;
                }
            }
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
            reply(player, ProbeReply.allowed(transferId, RelayConfig.safeChunkBytes(),
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
        // 传输途中被授 0 级也要立刻停，不能把剩下的分片收完再入库。
        if (!RelayPermissions.canUseMod(player)) {
            abort(player, upload, UploadResult.REJECTED_POLICY, "你已被管理员禁用 relay 功能");
            return;
        }
        // 首片嗅探魔数：不是 YSM 容器、压缩包或 7z 的话当场中止，省掉整份带宽。
        if (chunk.seq == 0 && chunk.data != null && !looksLikeModelSource(chunk.data)) {
            abort(player, upload, UploadResult.REJECTED_IDENTITY, "文件内容不是 YSM 模型或支持的压缩包");
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

    /** 客户端主动放弃这次传输。释放槽位、删掉暂存文件，并回一条 CANCELLED。 */
    public void onAbort(ServerPlayer player, long transferId) {
        var upload = byTransfer.get(transferId);
        if (upload == null || !upload.playerId.equals(player.getUUID())) {
            return;
        }
        abort(player, upload, UploadResult.CANCELLED, "上传已取消");
        LOGGER.info("[ysm-relay] 玩家 {} 取消了上传 {}", player.getGameProfile().getName(), transferId);
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

    /**
     * 首片魔数判定。
     *
     * <p>接受四种开头：当前容器 / 旧版 V3 / 旧版 raw {@code YSGP} / zip / 7z。
     * 判断只看前 8 个字节，不做完整解析——完整解析在导入阶段做，这里只挡明显的垃圾文件。
     */
    private static boolean looksLikeModelSource(byte[] head) {
        return startsWith(head, new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'Y', 'S', 'G', 'P'})
            || startsWith(head, new byte[] {'Y', 'S', 'G', 'P'})
            || startsWith(head, new byte[] {'P', 'K', 3, 4})
            || startsWith(head, new byte[] {'P', 'K', 5, 6})
            || startsWith(head, new byte[] {0x37, 0x7A, (byte) 0xBC, (byte) 0xAF});
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (var index = 0; index < prefix.length; index++) {
            if (data[index] != prefix[index]) {
                return false;
            }
        }
        return true;
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

            // 判重：catalog 里有、或刚写完还没重扫（pendingCommits），都算已有。
            if (YsmBridge.serverModelIds().contains(declared) || pendingCommits.containsKey(declared)) {
                complete(player, upload, UploadResult.ALREADY_AVAILABLE, declared.bytes(), "",
                    "服务端已有相同内容的模型");
                return;
            }

            var format = ModelIdentity.detect(upload.stagingFile);

            // 当前格式容器（YSGP2）：YSM 把它当「成品直放容器」，准入要求内嵌预览。
            // 没有预览的话，放进 custom 也只会在重扫时被拒（日志里是
            // Direct model container has no embedded preview），所以这里先自己验。
            if (format == ModelIdentity.Format.CURRENT_CONTAINER) {
                var container = com.elfmcys.ysm.model.storage.ManagedContainer.openDirect(upload.stagingFile,
                    com.elfmcys.ysm.model.catalog.source.CatalogLocation.DEFAULT);
                try {
                    if (!container.modelId().equals(declared)) {
                        complete(player, upload, UploadResult.REJECTED_IDENTITY, null, "",
                            "容器声明的模型标识与上传声明不符");
                        return;
                    }
                    try {
                        com.elfmcys.ysm.model.catalog.content.DirectContainerAdmission
                            .requireEmbeddedPreview(container);
                    } catch (IOException noPreview) {
                        complete(player, upload, UploadResult.REJECTED_IDENTITY, null, "",
                            "这个容器没有内嵌预览，YSM 不会把它当作成品模型接收");
                        return;
                    }
                } finally {
                    container.representation().close();
                }
                submit(player, upload, declared, upload.stagingFile, ".mxc");
                return;
            }

            // 旧版容器与压缩包：不要自己转码。YSM 自己会把 custom 下的 .ysm/.zip 当作
            // 「模型源」导入并建立索引，这条路不要求内嵌预览；而我们事先转码出来的成品
            // 会被当成直放容器，一旦缺预览就被拒。原样投放即可。
            submit(player, upload, declared, upload.stagingFile, extensionOf(upload));
        } catch (Throwable failure) {
            LOGGER.warn("[ysm-relay] 导入上传内容失败 transfer={}", upload.transferId, failure);
            complete(player, upload, UploadResult.FAILED_CONVERSION, null, "", String.valueOf(failure.getMessage()));
        } finally {
            upload.discard();
        }
    }

    /** 保留原始扩展名，YSM 的发现逻辑按扩展名决定走哪条导入路径。 */
    private static String extensionOf(ServerUpload upload) {
        var name = upload.originalName == null ? "" : upload.originalName.toLowerCase(Locale.ROOT);
        for (var allowed : ALLOWED_EXTENSIONS) {
            // allowed 自带宽点（".zip"），这里再拼一个点会变成 "..zip"，永远匹配不上。
            if (name.endsWith(allowed)) {
                return allowed;
            }
        }
        return ".ysm";
    }

    /**
     * 提交侧的唯一出口：决定这份内容直接进模型目录，还是先进待审队列。
     *
     * <p>判定顺序：白名单永远免审；否则看模式 —— {@code review} 全部待审，
     * {@code auto} / {@code full} 在 {@code autoApproveMaxBytes} 以内免审。
     * {@code full} 档超上限的请求在探测阶段就被拒绝了，走不到这里。
     *
     * <p>落盘时保留玩家原始文件名（重名加序号），因此 YSM 列表里显示的是正常名字而不是
     * 一串模型标识。文件名只做最小清理：去路径分隔符、非法字符与控制字符。
     */
    void submit(ServerPlayer player, ServerUpload upload, Hash256 modelId, java.nio.file.Path source,
        String extension) {
        var cap = RelayConfig.autoApproveMaxBytes();
        var mode = RelayConfig.uploadMode();
        // 白名单永远免审；否则看模式：review 全待审，auto/full 在上限内免审。
        // （full 模式下超上限的已经在探测阶段被拒了，走不到这里。）
        var auto = RelayPermissions.isUploadWhitelisted(player)
            || (!mode.equals("review") && (cap <= 0L || upload.expectedSize <= cap));
        if (auto) {
            var destination = RelayConfig.targetAuthRoot() ? RelayPaths.authRoot() : RelayPaths.uploadRoot();
            try {
                Files.createDirectories(destination);
                var landed = land(source, destination, safeName(upload.originalName, modelId, extension));
                pendingCommits.put(modelId, landed);
                requestReload();
                UploadRegistry.get().record(modelId, player, upload.originalName, upload.expectedSize);
                grantToUploader(player, modelId);
                long size = -1L;
                try {
                    size = Files.size(landed);
                } catch (IOException ignored) {
                    // 统计不到不影响结果
                }
                complete(player, upload, UploadResult.COMMITTED, modelId.bytes(),
                    landed.getFileName().toString(), "");
                LOGGER.info("[ysm-relay] 玩家 {} 上传模型成功 id={} path={} size={}",
                    player.getGameProfile().getName(), modelId, landed, size);
            } catch (IOException failure) {
                LOGGER.warn("[ysm-relay] 模型落盘失败 transfer={}", upload.transferId, failure);
                complete(player, upload, UploadResult.FAILED_CONVERSION, null, "", "服务端无法写入模型目录");
            }
            return;
        }
        // 审批模式：内容先进待审目录，服主决定之后才进 catalog。
        try {
            var reviewId = UUID.randomUUID().toString().substring(0, 8);
            var directory = PendingQueue.directoryOf(reviewId);
            Files.createDirectories(directory);
            var target = land(source, directory, safeName(upload.originalName, modelId, extension));
            var hash = modelId.toString();
            PendingQueue.get().add(new PendingQueue.Entry(reviewId, hash, player.getUUID().toString(),
                player.getGameProfile().getName(), upload.originalName, upload.expectedSize,
                reviewId + "/" + target.getFileName(), System.currentTimeMillis()));
            complete(player, upload, UploadResult.PENDING_REVIEW, modelId.bytes(), reviewId,
                "已收下，等待服主审核");
            LOGGER.info("[ysm-relay] {} 的上传进入待审队列 review={} id={}",
                player.getGameProfile().getName(), reviewId, hash);
        } catch (IOException failure) {
            LOGGER.warn("[ysm-relay] 待审落盘失败 review transfer={}", upload.transferId, failure);
            complete(player, upload, UploadResult.FAILED_CONVERSION, null, "", "服务端无法写入待审目录");
        }
    }

    /**
     * 模式切成自动/全自动后，把队列里已经存在的条目按新规则处理一遍。
     *
     * <p>不处理的话，切档只影响之后的新上传，队列里那批会一直躺着——这正是玩家看到的
     * 「切了自动模式，之前传的还得手动批」。
     *
     * @return 处理结果的简短描述
     */
    public String drainPending(String mode) {
        var cap = RelayConfig.autoApproveMaxBytes();
        var approved = 0;
        var rejected = 0;
        var kept = 0;
        for (var entry : PendingQueue.get().list()) {
            var underCap = cap <= 0L || entry.size() <= cap;
            if (underCap) {
                approve(entry.reviewId());
                approved++;
            } else if (mode.equals("full")) {
                reject(entry.reviewId());
                rejected++;
            } else {
                // 自动同意档：超上限的继续等人批
                kept++;
            }
        }
        var summary = "[ysm-relay] 切到 " + mode + " 档，队列处理：批准 " + approved
            + "，拒绝 " + rejected + "，仍需人工 " + kept;
        LOGGER.info(summary);
        return summary;
    }

    /** 本人撤回一条待审：内容直接删掉，不进禁止上传名单。 */
    public String withdraw(String reviewId, java.util.UUID uploaderId) {
        if (!PendingQueue.validReviewId(reviewId)) {
            return "编号不合法";
        }
        var entry = PendingQueue.get().find(reviewId);
        if (entry == null) {
            return "没有这个待审条目";
        }
        var removed = PendingQueue.get().withdraw(reviewId, uploaderId.toString());
        if (removed == null) {
            return "只能取消自己上传的内容";
        }
        return "已取消 " + reviewId + "（" + removed.fileName() + "）";
    }

    /** 批准一条待审：把文件搬进模型根、记台账、触发重扫，并通知上传者。 */
    public String approve(String reviewId) {
        if (!PendingQueue.validReviewId(reviewId)) {
            return "编号不合法";
        }
        var entry = PendingQueue.get().find(reviewId);
        if (entry == null) {
            return "没有这个待审条目";
        }
        var directory = PendingQueue.directoryOf(reviewId);
        try {
            var files = Files.list(directory).filter(Files::isRegularFile).toList();
            if (files.isEmpty()) {
                PendingQueue.get().removeEntry(reviewId);
                deleteQuietly(directory);
                return "待审文件已丢失，记录已清理";
            }
            var target = RelayConfig.targetAuthRoot() ? RelayPaths.authRoot() : RelayPaths.uploadRoot();
            Files.createDirectories(target);
            var source = files.get(0);
            var destination = land(source, target,
                safeName(entry.fileName(), Hash256.parse(entry.modelId()),
                    extensionOfName(source.getFileName().toString())));
            var hash = Hash256.parse(entry.modelId());
            pendingCommits.put(hash, destination);
            UploadRegistry.get().record(hash, entry.uploaderId(), entry.uploaderName(), entry.fileName(),
                entry.size());
            PendingQueue.get().removeEntry(reviewId);
            deleteQuietly(directory);
            requestReload();
            notifyUploader(entry, "你的模型已被服主批准入库：" + entry.fileName());
            return "已批准 " + reviewId + "（" + entry.fileName() + "）";
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("[ysm-relay] 批准待审失败 review={}", reviewId, failure);
            return "批准失败：" + failure.getMessage();
        }
    }

    /** 拒绝一条待审：删掉内容并把记录摘掉。 */
    public String reject(String reviewId) {
        if (!PendingQueue.validReviewId(reviewId)) {
            return "编号不合法";
        }
        var entry = PendingQueue.get().find(reviewId);
        if (entry == null) {
            return "没有这个待审条目";
        }
        PendingQueue.get().removeEntry(reviewId);
        deleteQuietly(PendingQueue.directoryOf(reviewId));
        notifyUploader(entry, "你的模型被服主拒绝了：" + entry.fileName());
        return "已拒绝 " + reviewId + "（" + entry.fileName() + "）";
    }

    /** 批准/拒绝之后告诉上传者（他可能已经离线，此时静默跳过）。 */
    private static void notifyUploader(PendingQueue.Entry entry, String text) {
        try {
            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return;
            }
            var player = server.getPlayerList().getPlayer(java.util.UUID.fromString(entry.uploaderId()));
            if (player != null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[ysm-relay] " + text));
            }
        } catch (RuntimeException ignored) {
            // 通知失败不影响审批结果
        }
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
        // 自动同步关闭时文件照样落盘，只是不 reload —— 等服主手动同步。
        if (!RelayConfig.autoSync()) {
            LOGGER.info("[ysm-relay] 自动同步已关闭，本次入库不触发 reload");
            // 不 reload 就无法核对，先留着 pendingCommits：它同时承担「重扫前判重」的作用。
            return;
        }
        // 合并窗口取「固定 500ms」与「服主配的最短间隔」中较大的那个，
        // 连续上传会被合并到同一个窗口，避免十个人上传扫十遍。
        var window = Math.max(RELOAD_MERGE_WINDOW_MS, RelayConfig.autoSyncMinIntervalMillis());
        var due = System.currentTimeMillis() + window;
        reloadDueAt.updateAndGet(previous -> Math.max(previous, due));
        if (reloadScheduled.compareAndSet(false, true)) {
            reloader.schedule(this::runReload, window, TimeUnit.MILLISECONDS);
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
            if (!Files.exists(file)) {
                // 文件已经不在了（外部删除或上次启动清理），这里无事可做。
                UploadRegistry.get().remove(modelId);
                continue;
            }
            var committed = YsmBridge.modelIdOfFile(file).filter(modelId::equals).isPresent();
            if (committed) {
                LOGGER.info("[ysm-relay] 入库确认 id={} file={}", modelId, file.getFileName());
                continue;
            }
            // 查不到不等于文件没用：旧版容器与压缩包是 YSM 的「模型源」，由它自己导入并
            // 建索引，用直放容器的读法去核对这些文件必然查不到。以前这里直接删文件，
            // 结果就是每上传一次，上一次的模型连同源文件一起消失。
            // 现在只保留文件并提示，绝不因为「核对不上」删除玩家上传的内容。
            LOGGER.warn("[ysm-relay] 未能在目录里确认该模型 id={} file={}（文件已保留，请检查 YSM 扫描错误）",
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

    /**
     * 落盘用的文件名：优先保留玩家原本的文件名。
     *
     * <p>用模型标识（一串十六进制）当文件名时，YSM 的列表会把文件名当显示名，
     * 玩家看到的就是一长串十六进制，看起来像乱码。这里保留原名，只做必要的清理。
     */
    static String safeName(String original, Hash256 modelId, String extension) {
        var ext = extension == null || extension.isEmpty() ? ".ysm" : extension;
        var base = original == null ? "" : original.trim();
        var slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        var lowered = base.toLowerCase(Locale.ROOT);
        for (var allowed : ALLOWED_EXTENSIONS) {
            if (lowered.endsWith(allowed)) {
                base = base.substring(0, base.length() - allowed.length());
                break;
            }
        }
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (base.isEmpty()) {
            base = modelId.toString();
        }
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        return base + ext;
    }

    /**
     * 把文件搬进目标目录，绝不覆盖已有文件。
     *
     * <p>{@link #uniqueTarget} 只保证「此刻不重名」，两个人同时上传同名文件时仍可能选到同一个
     * 落点。所以这里不用 REPLACE_EXISTING：撞上了就重新挑一个名字再试。
     */
    private static Path land(Path source, Path directory, String fileName) throws IOException {
        for (var attempt = 0; attempt < 8; attempt++) {
            var candidate = uniqueTarget(directory, fileName);
            try {
                Files.move(source, candidate);
                return candidate;
            } catch (java.nio.file.FileAlreadyExistsException race) {
                // 另一个线程刚占了这个名字，换下一个
            }
        }
        throw new IOException("无法在目标目录里找到不重名的文件名：" + fileName);
    }

    /** 重名时加序号，绝不覆盖已在目录里的文件。 */
    static Path uniqueTarget(Path directory, String fileName) {
        var dot = fileName.lastIndexOf('.');
        var stem = dot < 0 ? fileName : fileName.substring(0, dot);
        var ext = dot < 0 ? "" : fileName.substring(dot);
        var candidate = directory.resolve(fileName);
        var index = 2;
        while (Files.exists(candidate) && index < 100) {
            candidate = directory.resolve(stem + " (" + index + ")" + ext);
            index++;
        }
        return candidate;
    }

    /** 从文件名取扩展名，保留大小写不敏感的原样值。 */
    static String extensionOfName(String name) {
        var dot = name.lastIndexOf('.');
        return dot < 0 ? ".ysm" : name.substring(dot);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 残留让 TTL 和 reload 的错误报告去兜
        }
    }

    /** 把字节数格式化成给人看的 MB。只用于提示文本，不参与任何配额判定。 */
    private static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
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
