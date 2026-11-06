package com.asu.ysmrelay.net.msg;

import com.asu.ysmrelay.upload.UploadService;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：一个数据分片。
 *
 * <p>分片大小由服务端在 {@link ProbeReply#chunkSize} 里给出，客户端不要自己定。服务端按
 * {@code transferId} 顺序追加写 staging 文件，{@code last} 到达后触发校验与导入。
 */
public final class UploadChunk {
    /** 单包硬上限。channel 走 Minecraft 的 1 MiB 包上限，这里留足够余量。 */
    public static final int MAX_DATA_BYTES = 16 * 1024;

    public final long transferId;
    public final int seq;
    public final boolean last;
    public final byte[] data;

    public UploadChunk(long transferId, int seq, boolean last, byte[] data) {
        this.transferId = transferId;
        this.seq = seq;
        this.last = last;
        this.data = data;
    }

    public static void encode(UploadChunk message, FriendlyByteBuf buffer) {
        buffer.writeVarLong(message.transferId);
        buffer.writeVarInt(message.seq);
        buffer.writeBoolean(message.last);
        buffer.writeByteArray(message.data);
    }

    public static UploadChunk decode(FriendlyByteBuf buffer) {
        var transferId = buffer.readVarLong();
        var seq = buffer.readVarInt();
        var last = buffer.readBoolean();
        var data = buffer.readByteArray(MAX_DATA_BYTES);
        return new UploadChunk(transferId, seq, last, data);
    }

    public static void handle(UploadChunk message, Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var sender = context.getSender();
        if (sender == null) {
            return;
        }
        UploadService.instance().onChunk(sender, message);
    }
}
