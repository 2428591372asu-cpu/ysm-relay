package com.asu.ysmrelay.client;

import com.asu.ysmrelay.net.RelayNetwork;
import com.asu.ysmrelay.net.msg.ProbeReply;
import com.asu.ysmrelay.net.msg.UploadAbort;
import com.asu.ysmrelay.net.msg.UploadChunk;
import com.asu.ysmrelay.net.msg.UploadProbe;
import com.asu.ysmrelay.net.msg.UploadProgress;
import com.asu.ysmrelay.net.msg.UploadResult;
import com.asu.ysmrelay.upload.ModelIdentity;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端上传状态机。
 *
 * <p>只在客户端加载。所有 IO（算模型标识、读文件切块）都在后台线程，主线程只碰 UI 和网络发送。
 *
 * <h2>一次一批，逐个串行</h2>
 * <p>服务端按「每人同时只允许 1 个未完成 transfer」准入，所以客户端也不并发：一次 enqueue 一批文件，
 * 前一个结束（成功、被拒、失败都算结束）后自动取下一个。{@link #busy()} 在整个批次期间为真。
 *
 * <h2>为什么要有 {@link Outcome}</h2>
 * <p>队列化以后如果每个文件都往聊天栏发一条，10 个模型就是 10 条，把玩家的聊天刷没。
 * 所以每个文件的结局记在 {@link #outcomeOf(Path)} 里给面板逐行画状态位，整批结束时只发一条汇总。
 * 单文件批次例外：直接报那一句具体原因，否则玩家看不到「为什么没传上去」。
 *
 * <p>状态码一律用 {@link UploadResult} 里的常量，不在这里另立编号。
 */
public final class ClientUploads {
    private static final Logger LOGGER = LoggerFactory.getLogger("ysm_relay");

    public enum Phase {
        IDLE, HASHING, PROBING, SENDING, DONE
    }

    /** 单个文件在这一批里的结局。面板据此给每行画状态位。 */
    public enum Outcome {
        /** 已入队，还没轮到。 */
        QUEUED,
        /** 正在算标识 / 探测 / 传输。 */
        RUNNING,
        /** 服务端已入库。 */
        COMMITTED,
        /** 服务端已有同内容模型。 */
        ALREADY,
        /** 内容已收下，等服主审批。 */
        AWAITING,
        /** 被服务端拒绝（配额、策略、标识不符等）。 */
        REJECTED,
        /** 客户端或服务端出错。 */
        FAILED,
        /** 玩家主动取消。 */
        CANCELLED
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "ysm-relay-client-upload");
        thread.setDaemon(true);
        return thread;
    });

    /** 让出时单次最长睡多久。分段睡是为了取消能在一小段内生效。 */
    private static final long PACING_SLICE_MILLIS = 50L;

    private static volatile Phase phase = Phase.IDLE;
    private static volatile String statusLine = "";
    private static volatile float progress;

    private static volatile Path currentFile;
    private static volatile byte[] currentModelId;
    private static volatile long transferId;
    private static volatile int chunkSize;
    /**
     * 服务端在 {@code ProbeReply} 里下发的上行速率上限，字节/秒，0 表示不限。
     *
     * <p>只在 ALLOWED 分支里被赋值。**不许在这里写死一个默认值**：服主把上限调成多少，
     * 客户端就按多少走；客户端自己加保险丝会和配置打架，真出限速问题时也说不清是谁在限。
     */
    private static volatile int rateLimitBytesPerSecond;

    /** 等待中的文件。所有增删都在 {@code synchronized (pending)} 内。 */
    private static final ArrayDeque<Path> pending = new ArrayDeque<>();
    /** 每个文件的结局。面板每帧读，用并发 map 免得跟队列抢锁。 */
    private static final Map<Path, Outcome> outcomes = new ConcurrentHashMap<>();
    /** 这一批一共几个。UI 用它算「第几个 / 共几个」。 */
    private static volatile int batchTotal;
    /** 最后一个文件结束时的具体原因，单文件批次直接汇报它。 */
    private static volatile String lastDetail = "";

    private static final AtomicBoolean cancelled = new AtomicBoolean();

    private ClientUploads() {}

    public static Phase phase() {
        return phase;
    }

    public static String statusLine() {
        return statusLine;
    }

    public static float progress() {
        return progress;
    }

    /** 当前是否还有上传在跑（含队列里还没轮到的）。UI 用它决定要不要画进度条。 */
    public static boolean busy() {
        var snapshot = phase;
        return snapshot == Phase.HASHING || snapshot == Phase.PROBING || snapshot == Phase.SENDING;
    }

    /** 队列里还没开始的个数，不含正在传的那个。 */
    public static int queuedCount() {
        synchronized (pending) {
            return pending.size();
        }
    }

    /** 某个文件在这一批里的结局；没参与这一批返回 null。UI 每帧调用，必须廉价。 */
    public static Outcome outcomeOf(Path file) {
        return file == null ? null : outcomes.get(file);
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 开始上传一个本地模型文件。内部就是单元素的 {@link #enqueue(List)}。
     *
     * @return 拒绝原因；返回 null 表示已经开始
     */
    public static String start(Path file) {
        return file == null ? "找不到文件" : enqueue(List.of(file));
    }

    /**
     * 把一批文件排进上传队列，立刻开始第一个。立刻返回。
     *
     * @return 拒绝原因；返回 null 表示已开始
     */
    public static String enqueue(List<Path> files) {
        if (busy()) {
            return "已有一次上传在进行中";
        }
        if (Minecraft.getInstance().getConnection() == null) {
            return "还没有连上服务器";
        }
        var accepted = new java.util.ArrayList<Path>();
        if (files != null) {
            for (var file : files) {
                if (file != null && Files.isRegularFile(file)) {
                    accepted.add(file);
                }
            }
        }
        if (accepted.isEmpty()) {
            return "选中的文件都不存在了";
        }

        cancelled.set(false);
        outcomes.clear();
        synchronized (pending) {
            pending.clear();
            for (var file : accepted) {
                pending.add(file);
                outcomes.put(file, Outcome.QUEUED);
            }
        }
        batchTotal = accepted.size();
        lastDetail = "";
        progress = 0.0F;
        advance();
        return null;
    }

    /** 取消这一整批：清掉还没开始的，并让正在传的那个停下来。 */
    public static void cancel() {
        if (!busy()) {
            say("当前没有进行中的上传");
            return;
        }
        cancelled.set(true);
        synchronized (pending) {
            for (var file : pending) {
                outcomes.put(file, Outcome.CANCELLED);
            }
            pending.clear();
        }
        // 通知服务端释放槽位：不发这条的话，玩家取消后再传会被判 REJECTED_BUSY。
        var active = transferId;
        if (active != 0L) {
            RelayNetwork.toServer(new UploadAbort(active));
        }
        // HASHING / PROBING 没有发送循环在跑，必须在这里收尾；
        // SENDING 交给发送线程：它看到取消位会自己 finish，这里抢先收尾会重复。
        var snapshot = phase;
        if (snapshot == Phase.HASHING || snapshot == Phase.PROBING) {
            finish("已取消", Outcome.CANCELLED);
        }
    }

    /** 取队列里的下一个开始，队列空了就收尾。持有 pending 锁调用。 */
    private static void advance() {
        Path next;
        synchronized (pending) {
            next = pending.poll();
        }
        if (next == null) {
            reportBatch();
            return;
        }
        outcomes.put(next, Outcome.RUNNING);
        startOne(next);
    }

    /** 真正开始传一个文件。与旧的 start 逻辑一致，只是不再做 busy 判定。 */
    private static void startOne(Path file) {
        cancelled.set(false);
        currentFile = file;
        currentModelId = null;
        transferId = 0L;
        chunkSize = 0;
        rateLimitBytesPerSecond = 0;
        progress = 0.0F;
        phase = Phase.HASHING;
        statusLine = batchTotal > 1 ? file.getFileName() + "：正在计算模型标识…" : "正在计算模型标识…";

        IO.submit(() -> {
            try {
                var id = ModelIdentity.of(file);
                if (cancelled.get()) {
                    finish("已取消", Outcome.CANCELLED);
                    return;
                }
                currentModelId = id.bytes();
                phase = Phase.PROBING;
                statusLine = "正在询问服务端…";
                RelayNetwork.toServer(new UploadProbe(id.bytes(), Files.size(file), file.getFileName().toString()));
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("[ysm-relay] 计算模型标识失败 {}", file, failure);
                finish("无法解析这个模型文件：" + failure.getMessage(), Outcome.FAILED);
            }
        });
    }

    // ------------------------------------------------------------------
    // 网络回执
    // ------------------------------------------------------------------

    /**
     * probe 回执。只认 {@link ProbeReply#ALLOWED} 和 5 个终止态拒绝。
     *
     * <p>服务端若违反协议回了终态成功值，走 {@code default} 当无效响应 —— 客户端不做容错猜测。
     */
    public static void handleProbeReply(ProbeReply message) {
        if (!busy()) {
            return;
        }
        if (message.allowed()) {
            transferId = message.transferId;
            // 服务端给多少都要再夹一次：单包超过 32767 字节会被 MC 判非法并直接断线。
        chunkSize = Math.max(4096, Math.min(16 * 1024, message.chunkSize));
            rateLimitBytesPerSecond = Math.max(0, message.rateLimitBytesPerSecond);
            phase = Phase.SENDING;
            statusLine = rateLimitBytesPerSecond > 0
                ? "上传中（限速 " + mibPerSecond(rateLimitBytesPerSecond) + "）…"
                : "上传中…";
            IO.submit(ClientUploads::sendChunks);
            return;
        }
        switch (message.status) {
            case UploadResult.ALREADY_AVAILABLE -> finish("服务端已有相同内容的模型，无需上传", Outcome.ALREADY);
            case UploadResult.REJECTED_QUOTA -> finish(orDefault(message.message, "模型超过服务端允许的大小"), Outcome.REJECTED);
            case UploadResult.REJECTED_POLICY -> finish(orDefault(message.message, "服务端拒绝了这次上传"), Outcome.REJECTED);
            case UploadResult.REJECTED_BUSY -> finish("你已有一次上传在进行中", Outcome.REJECTED);
            case UploadResult.REJECTED_IDENTITY -> finish(orDefault(message.message, "服务端不认这个文件"), Outcome.REJECTED);
            case UploadResult.REJECTED_QUEUE_FULL ->
                finish(orDefault(message.message, "服务端待审队列已满，请稍后再试"), Outcome.REJECTED);
            default -> finish(orDefault(message.message, "服务端返回了无效响应"), Outcome.FAILED);
        }
    }

    public static void handleProgress(UploadProgress message) {
        if (message.transferId != transferId) {
            return;
        }
        progress = message.fraction();
    }

    public static void handleResult(UploadResult message) {
        if (!busy()) {
            return;
        }
        // transferId 必须严格相等。旧代码写成 `transferId != 0L` 当例外，
        // 导致本地 transferId 还是 0 时任何一条结果包都会被当成自己的。
        if (message.transferId != transferId) {
            return;
        }
        progress = 1.0F;
        switch (message.status) {
            case UploadResult.COMMITTED -> finish("上传成功，模型已进入服务端目录", Outcome.COMMITTED);
            case UploadResult.ALREADY_AVAILABLE -> finish("服务端已有相同内容的模型，未重复保存", Outcome.ALREADY);
            case UploadResult.PENDING_REVIEW -> finish("已收下，正在等服主审批", Outcome.AWAITING);
            case UploadResult.REJECTED_BY_OWNER -> finish(orDefault(message.message, "服主拒绝了这次上传"), Outcome.REJECTED);
            case UploadResult.REJECTED_POLICY -> finish(orDefault(message.message, "服务端策略拒绝了这次上传"), Outcome.REJECTED);
            case UploadResult.REJECTED_QUOTA -> finish(orDefault(message.message, "超出服务端允许的配额"), Outcome.REJECTED);
            case UploadResult.REJECTED_BUSY -> finish("你已有一次上传在进行中", Outcome.REJECTED);
            case UploadResult.REJECTED_IDENTITY ->
                finish(orDefault(message.message, "服务端判定内容与服务端算出的标识不符"), Outcome.REJECTED);
            case UploadResult.REJECTED_QUEUE_FULL -> finish(orDefault(message.message, "服务端待审队列已满"), Outcome.REJECTED);
            case UploadResult.FAILED_CONVERSION -> finish(orDefault(message.message, "服务端导入失败"), Outcome.FAILED);
            case UploadResult.CANCELLED -> finish("上传已取消", Outcome.CANCELLED);
            default -> finish("上传结束：" + message.message, Outcome.FAILED);
        }
    }

    // ------------------------------------------------------------------
    // 发送循环（后台线程）
    // ------------------------------------------------------------------

    private static void sendChunks() {
        var file = currentFile;
        var activeTransfer = transferId;
        if (file == null || activeTransfer == 0L) {
            finish("上传状态丢失", Outcome.FAILED);
            return;
        }
        var limit = rateLimitBytesPerSecond;
        var startedNanos = System.nanoTime();
        try (InputStream input = Files.newInputStream(file)) {
            var total = Files.size(file);
            var buffer = new byte[chunkSize];
            var seq = 0;
            long sent = 0L;
            while (!cancelled.get()) {
                var read = input.readNBytes(buffer, 0, chunkSize);
                if (read <= 0) {
                    break;
                }
                sent += read;
                var last = sent >= total;
                var payload = Arrays.copyOf(buffer, read);
                RelayNetwork.toServer(new UploadChunk(activeTransfer, seq++, last, payload));
                progress = total <= 0L ? 1.0F : (float) sent / (float) total;
                if (last) {
                    statusLine = "服务端正在导入…";
                    return;
                }
                // 服务端下发了速率就按速率让出。让出期间必须能被打断，
                // 否则玩家点了取消要等一整段睡眠结束才响应。
                pace(sent, startedNanos, limit);
            }
            if (cancelled.get()) {
                finish("已取消", Outcome.CANCELLED);
            } else if (sent < total) {
                finish("文件在传输过程中变短了", Outcome.FAILED);
            }
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("[ysm-relay] 上传发送失败 {}", file, failure);
            finish("发送失败：" + failure.getMessage(), Outcome.FAILED);
        }
    }

    /**
     * 按「已发字节 ÷ 允许速率」算出此刻应该用了多久，落后就分段睡过去。
     *
     * <p>{@code limit <= 0} 时立即返回（服务端没下发上限就是不限速）。睡眠切成小片，
     * 每片之后都重新看 {@code cancelled}，保证取消最多等一片。
     */
    private static void pace(long sentBytes, long startedNanos, int limit) {
        if (limit <= 0) {
            return;
        }
        var budgetNanos = sentBytes * 1_000_000_000L / limit;
        var behindNanos = budgetNanos - (System.nanoTime() - startedNanos);
        while (behindNanos > 0L && !cancelled.get()) {
            var sliceMillis = Math.max(1L, Math.min(PACING_SLICE_MILLIS, behindNanos / 1_000_000L));
            try {
                Thread.sleep(sliceMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            behindNanos -= sliceMillis * 1_000_000L;
        }
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------

    /**
     * 结束当前文件：记结局、清取消位，然后要么取下一个，要么整批收尾。
     *
     * <p>会被主线程（probe / result 回执）和后台 IO 线程同时调用，所以整体同步。
     */
    private static synchronized void finish(String line, Outcome outcome) {
        var file = currentFile;
        if (file != null) {
            outcomes.put(file, outcome);
        }
        lastDetail = line == null ? "" : line;
        statusLine = lastDetail;
        // 取消位不在这里清：发送线程的循环条件是 !cancelled，先清掉会让它继续传。
        // 清位交给 startOne()，只在真正开始下一个文件时复位。
        advance();
    }

    /** 整批结束：发一条汇总，然后回 IDLE 让界面恢复可操作。 */
    private static void reportBatch() {
        phase = Phase.IDLE;
        progress = 0.0F;
        var total = batchTotal;
        if (total <= 0) {
            return;
        }
        // 单文件批次直接报具体原因 —— 汇总里只有「失败 1」，说不清为什么。
        if (total == 1) {
            statusLine = lastDetail;
            say(statusLine);
            batchTotal = 0;
            return;
        }
        int ok = 0;
        int dup = 0;
        int wait = 0;
        int rejected = 0;
        int failed = 0;
        int cancelledCount = 0;
        for (var value : outcomes.values()) {
            switch (value) {
                case COMMITTED -> ok++;
                case ALREADY -> dup++;
                case AWAITING -> wait++;
                case REJECTED -> rejected++;
                case FAILED -> failed++;
                case CANCELLED -> cancelledCount++;
                default -> { }
            }
        }
        var parts = new StringBuilder();
        appendCount(parts, "成功", ok);
        appendCount(parts, "已有", dup);
        appendCount(parts, "待审", wait);
        appendCount(parts, "被拒", rejected);
        appendCount(parts, "失败", failed);
        appendCount(parts, "取消", cancelledCount);
        statusLine = "这一批 " + total + " 个：" + (parts.length() == 0 ? "无结果" : parts.toString());
        say(statusLine);
        batchTotal = 0;
    }

    private static void appendCount(StringBuilder builder, String label, int count) {
        if (count <= 0) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("，");
        }
        builder.append(label).append(' ').append(count);
    }

    private static void say(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.literal("[ysm-relay] " + line), false);
        }
    }

    private static String orDefault(String text, String fallback) {
        return text == null || text.isEmpty() ? fallback : text;
    }

    private static String mibPerSecond(int bytesPerSecond) {
        return String.format("%.1f MiB/s", bytesPerSecond / (1024.0 * 1024.0));
    }
}
