package com.asu.ysmrelay.net.msg;

import com.asu.ysmrelay.upload.UploadService;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * 客户端 → 服务端：放弃这一次传输。
 *
 * <p>没有这条消息时，取消只发生在客户端：服务端 {@code byPlayer} 槽位要等到玩家断线才释放，
 * 玩家紧接着再传一次就会拿到 {@code REJECTED_BUSY}，表现为「取消之后传不了了」。
 */
public final class UploadAbort {
    public final long transferId;

    public UploadAbort(long transferId) {
        this.transferId = transferId;
    }

    public static void encode(UploadAbort message, FriendlyByteBuf buffer) {
        buffer.writeVarLong(message.transferId);
    }

    public static UploadAbort decode(FriendlyByteBuf buffer) {
        return new UploadAbort(buffer.readVarLong());
    }

    public static void handle(UploadAbort message, Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var sender = context.getSender();
        if (sender == null) {
            return;
        }
        UploadService.instance().onAbort(sender, message.transferId);
    }
}
