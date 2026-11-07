package com.asu.ysmrelay.net.msg;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/** 客户端 → 服务端：请求一份列表面（自己的上传 / 待审队列）。 */
public final class DataRequest {
    public static final int KIND_MINE = 0;
    public static final int KIND_PENDING = 1;
    public static final int KIND_MY_PENDING = 2;
    /** 服主「模型管理」列表：目录里所有模型 + 上传者。 */
    public static final int KIND_MANAGED = 3;

    public final int kind;

    public DataRequest(int kind) {
        this.kind = kind;
    }

    public static void encode(DataRequest message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.kind);
    }

    public static DataRequest decode(FriendlyByteBuf buffer) {
        return new DataRequest(buffer.readVarInt());
    }

    public static void handle(DataRequest message, Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var sender = context.getSender();
        if (sender == null) {
            return;
        }
        com.asu.ysmrelay.config.RelayLists.reply(sender, message.kind);
    }
}
