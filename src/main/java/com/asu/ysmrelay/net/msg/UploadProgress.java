package com.asu.ysmrelay.net.msg;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：进度回报。
 *
 * <p>不是每个分片都回，服务端按字节数节流（默认每 1 MiB 或每 20 个分片回一次），避免小文件
 * 把带宽全部花在进度包上。
 */
public final class UploadProgress {
    public final long transferId;
    public final long received;
    public final long total;

    public UploadProgress(long transferId, long received, long total) {
        this.transferId = transferId;
        this.received = received;
        this.total = total;
    }

    /** 0.0 ~ 1.0，总长为 0 时返回 1.0（视为已完成）。 */
    public float fraction() {
        if (total <= 0L) {
            return 1.0F;
        }
        return Math.min(1.0F, (float) received / (float) total);
    }

    public static void encode(UploadProgress message, FriendlyByteBuf buffer) {
        buffer.writeVarLong(message.transferId);
        buffer.writeVarLong(message.received);
        buffer.writeVarLong(message.total);
    }

    public static UploadProgress decode(FriendlyByteBuf buffer) {
        return new UploadProgress(buffer.readVarLong(), buffer.readVarLong(), buffer.readVarLong());
    }

    public static void handle(UploadProgress message, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> com.asu.ysmrelay.client.ClientUploads.handleProgress(message));
    }
}
