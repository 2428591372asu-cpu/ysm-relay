package com.asu.ysmrelay.net.msg;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/** 服务端 → 客户端：一份列表面。 */
public final class DataReply {
    /** 协议层能接受的最大条数；实际条数由服务端配置与字节预算再收一次。 */
    public static final int MAX_ENTRIES = 32767;
    /** 单包建议预算：超过就截断，避免一个列表包过大。 */
    public static final int MAX_BYTES = 24 * 1024;

    /** 一行。{@code extra} 对「我的上传」是空串，对待审是 reviewId。 */
    public record Entry(String modelId, String fileName, long size, String extra) {
    }

    public final int kind;
    public final List<Entry> entries;

    public DataReply(int kind, List<Entry> entries) {
        this.kind = kind;
        this.entries = entries;
    }

    public static void encode(DataReply message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.kind);
        buffer.writeVarInt(message.entries.size());
        for (var entry : message.entries) {
            buffer.writeUtf(entry.modelId(), 128);
            buffer.writeUtf(entry.fileName(), 256);
            buffer.writeVarLong(entry.size());
            buffer.writeUtf(entry.extra(), 128);
        }
    }

    public static DataReply decode(FriendlyByteBuf buffer) {
        var kind = buffer.readVarInt();
        var count = buffer.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("too many entries: " + count);
        }
        var entries = new ArrayList<Entry>(count);
        for (var index = 0; index < count; index++) {
            entries.add(new Entry(buffer.readUtf(128), buffer.readUtf(256), buffer.readVarLong(),
                buffer.readUtf(128)));
        }
        return new DataReply(kind, entries);
    }

    public static void handle(DataReply message, Supplier<NetworkEvent.Context> contextSupplier) {
        com.asu.ysmrelay.client.ClientLists.accept(message);
    }
}
