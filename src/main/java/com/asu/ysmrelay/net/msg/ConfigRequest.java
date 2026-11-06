package com.asu.ysmrelay.net.msg;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/** 客户端 → 服务端：请把当前分发设置快照发给我（打开设置页时用）。 */
public final class ConfigRequest {
    public static void encode(ConfigRequest message, FriendlyByteBuf buffer) {
        // 无字段
    }

    public static ConfigRequest decode(FriendlyByteBuf buffer) {
        return new ConfigRequest();
    }

    public static void handle(ConfigRequest message, Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var sender = context.getSender();
        if (sender == null) {
            return;
        }
        com.asu.ysmrelay.config.RelayConfigSync.send(sender);
    }
}
