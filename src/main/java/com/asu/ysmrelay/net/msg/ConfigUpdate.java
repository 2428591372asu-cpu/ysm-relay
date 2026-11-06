package com.asu.ysmrelay.net.msg;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * 客户端 → 服务端：请求修改若干分发设置。
 *
 * <p>只带「键 → 文本值」，服务端逐键解析并按角色校验；任何一键不合法就整批拒绝，
 * 不做部分生效——半套配置比没改更难排查。
 */
public final class ConfigUpdate {
    /** 单次提交的最大键数，防止用超长列表打服务端。 */
    public static final int MAX_KEYS = 32;
    public static final int MAX_TEXT = 128;

    public final Map<String, String> changes;

    public ConfigUpdate(Map<String, String> changes) {
        this.changes = changes;
    }

    public static void encode(ConfigUpdate message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.changes.size());
        for (var entry : message.changes.entrySet()) {
            buffer.writeUtf(entry.getKey(), MAX_TEXT);
            buffer.writeUtf(entry.getValue() == null ? "" : entry.getValue(), MAX_TEXT);
        }
    }

    public static ConfigUpdate decode(FriendlyByteBuf buffer) {
        var count = buffer.readVarInt();
        if (count < 0 || count > MAX_KEYS) {
            throw new IllegalArgumentException("too many config keys: " + count);
        }
        var changes = new LinkedHashMap<String, String>();
        for (var index = 0; index < count; index++) {
            changes.put(buffer.readUtf(MAX_TEXT), buffer.readUtf(MAX_TEXT));
        }
        return new ConfigUpdate(changes);
    }

    public static void handle(ConfigUpdate message, Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var sender = context.getSender();
        if (sender == null) {
            return;
        }
        com.asu.ysmrelay.config.RelayConfigSync.apply(sender, message);
    }
}
