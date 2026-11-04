package com.asu.ysmrelay.net.msg;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：探测结果 / 上传准入。
 *
 * <p>{@code ALLOWED} 时附带 {@code transferId}、{@code chunkSize} 和上行的速率上限，客户端据此
 * 开始切片。其余状态都是终止态，客户端只需把 {@code message} 显示给人看。
 *
 * <h2>{@code status} 的取值来自 {@link UploadResult}，不在这里另定义</h2>
 * <p>本类只多一个 {@link #ALLOWED}（= -1）：「准入通过」不是任何终态，不该占用终态值域里的号。
 * 其余取值一律写 {@code UploadResult.XXX}，客户端和日志里一张表就能对上。
 *
 * <p><b>probe 阶段只能回</b>：
 * {@link #ALLOWED} / {@link UploadResult#ALREADY_AVAILABLE} / {@link UploadResult#REJECTED_POLICY} /
 * {@link UploadResult#REJECTED_QUOTA} / {@link UploadResult#REJECTED_BUSY} /
 * {@link UploadResult#REJECTED_IDENTITY} / {@link UploadResult#REJECTED_QUEUE_FULL}。
 *
 * <p><b>不许回</b>{@link UploadResult#COMMITTED}、{@link UploadResult#PENDING_REVIEW}、
 * {@link UploadResult#REJECTED_BY_OWNER}、{@link UploadResult#FAILED_CONVERSION}、
 * {@link UploadResult#CANCELLED}。其中 {@code PENDING_REVIEW = 2} 最容易被误用，见
 * {@link UploadResult} 类注释里的原因：probe 阶段没有 transferId，回它等于让客户端永久等待。
 */
public final class ProbeReply {
    /** 可以传，按给出的 transferId / chunkSize / 速率开始切片。不占终态值域。 */
    public static final int ALLOWED = -1;

    public final int status;
    public final long transferId;
    public final int chunkSize;
    /**
     * 上行限速，单位字节/秒，{@code 0} 表示服务端不下发限制。
     *
     * <p>由服务端配置 {@code maxUploadBytesPerSecond} 决定。客户端只按这个值让出，
     * 不自己硬编码默认速率 —— 否则和服主配的值打架，出问题时还查不清是谁在限。
     */
    public final int rateLimitBytesPerSecond;
    public final String message;

    public ProbeReply(int status, long transferId, int chunkSize, int rateLimitBytesPerSecond, String message) {
        this.status = status;
        this.transferId = transferId;
        this.chunkSize = chunkSize;
        this.rateLimitBytesPerSecond = Math.max(0, rateLimitBytesPerSecond);
        this.message = message == null ? "" : message;
    }

    /**
     * 准入通过但不限速。
     *
     * <p>保留这个两参重载是有意的：它能切断 W1（协议）与 W2（配置键）之间的编译期依赖 ——
     * {@code UploadService} 先继续调两参版，W2 加上 {@code maxUploadBytesPerSecond} 键之后
     * 再切成三参版，两边各改各的、谁先合都不炸。
     */
    public static ProbeReply allowed(long transferId, int chunkSize) {
        return allowed(transferId, chunkSize, 0);
    }

    /** 准入通过并下发上行速率上限（0 = 不限速）。 */
    public static ProbeReply allowed(long transferId, int chunkSize, int rateLimitBytesPerSecond) {
        return new ProbeReply(ALLOWED, transferId, chunkSize, rateLimitBytesPerSecond, "");
    }

    /** 终止态的拒绝。{@code status} 必须取自 {@link UploadResult} 的允许清单。 */
    public static ProbeReply denied(int status, String message) {
        return new ProbeReply(status, 0L, 0, 0, message);
    }

    /** 是否准入通过。UI 与日志用它，避免各处写 {@code status == -1}。 */
    public boolean allowed() {
        return status == ALLOWED;
    }

    public static void encode(ProbeReply message, FriendlyByteBuf buffer) {
        // 定长字段在前、变长字段在后 —— 以后再加字段只能插在 message 之前。
        buffer.writeVarInt(message.status);
        buffer.writeVarLong(message.transferId);
        buffer.writeVarInt(message.chunkSize);
        buffer.writeVarInt(message.rateLimitBytesPerSecond);
        buffer.writeUtf(message.message, 512);
    }

    public static ProbeReply decode(FriendlyByteBuf buffer) {
        var status = buffer.readVarInt();
        var transferId = buffer.readVarLong();
        var chunkSize = buffer.readVarInt();
        var rate = buffer.readVarInt();
        var text = buffer.readUtf(512);
        return new ProbeReply(status, transferId, chunkSize, rate, text);
    }

    public static void handle(ProbeReply message, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> com.asu.ysmrelay.client.ClientUploads.handleProbeReply(message));
    }
}
