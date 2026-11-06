package com.asu.ysmrelay.config;

import com.asu.ysmrelay.net.RelayNetwork;
import com.asu.ysmrelay.net.msg.ConfigSnapshot;
import com.asu.ysmrelay.net.msg.ConfigUpdate;
import com.asu.ysmrelay.upload.UploadService;
import java.util.LinkedHashMap;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端分发设置的读写下发。
 *
 * <p>写只允许服主；读允许所有人（客户端要靠它决定哪些按钮置灰）。客户端拿到的只是提示，
 * 每次真实操作仍然在服务端重算权限。
 */
public final class RelayConfigSync {
    private RelayConfigSync() {}

    public static ConfigSnapshot snapshotFor(ServerPlayer player) {
        return new ConfigSnapshot(
            RelayConfig.rejectAllUploads(),
            RelayConfig.allowAutoUpload(),
            RelayConfig.autoApprove(),
            RelayConfig.autoSync(),
            RelayConfig.AUTO_SYNC_MIN_INTERVAL_SECONDS.get(),
            RelayConfig.CHUNK_BYTES.get(),
            RelayConfig.maxModelBytes(),
            RelayConfig.autoApproveMaxBytes(),
            RelayPermissions.canConfigure(player),
            RelayPermissions.canApprove(player),
            RelayPermissions.canManage(player),
            RelayConfig.autoUploadScanSeconds(),
            RelayPermissions.effectiveLevel(player),
            RelayPermissions.isHost(player),
            RelayConfig.optionLevels(),
            RelayConfig.listMaxEntries(),
            RelayConfig.maxPendingPerPlayer(),
            "review".equals(RelayConfig.uploadMode()) ? 0
                : "auto".equals(RelayConfig.uploadMode()) ? 1 : 2);
    }

    /** 把快照发给单个玩家。 */
    public static void send(ServerPlayer player) {
        try {
            RelayNetwork.toPlayer(player, snapshotFor(player));
        } catch (RuntimeException ignored) {
            // 玩家正好断开，没有可做的
        }
    }

    /** 广播给所有在线玩家（配置改动后）。 */
    public static void broadcast(MinecraftServer server) {
        for (var player : server.getPlayerList().getPlayers()) {
            send(player);
        }
    }

    /**
     * 处理一次配置提交。
     *
     * <p>整批校验：任何一键不合法就整批拒绝，不做部分生效——半套配置比没改更难排查。
     */
    public static void apply(ServerPlayer player, ConfigUpdate update) {
        var level = RelayPermissions.effectiveLevel(player);
        // 逐项按各自等级校验；一项不够就整批拒绝。
        for (var key : update.changes.keySet()) {
            var option = optionOf(key);
            if (option == null || level < RelayConfig.levelOf(option)) {
                actionBar(player, "等级不足，无法修改 " + key);
                return;
            }
        }
        if (update.changes.isEmpty()) {
            return;
        }
        var parsed = new LinkedHashMap<String, Object>();
        for (var entry : update.changes.entrySet()) {
            var key = entry.getKey() == null ? "" : entry.getKey().trim();
            var raw = entry.getValue() == null ? "" : entry.getValue().trim();
            try {
                switch (key) {
                    case "rejectAllUploads", "allowAutoUpload", "autoSync" ->
                        parsed.put(key, parseBoolean(key, raw));
                    case "uploadMode" -> {
                        var text = raw.toLowerCase(java.util.Locale.ROOT);
                        if (!text.equals("review") && !text.equals("auto") && !text.equals("full")) {
                            throw new IllegalArgumentException("bad mode");
                        }
                        parsed.put(key, text);
                    }
                    case "autoSyncMinIntervalSeconds" ->
                        parsed.put(key, clampInt(key, raw, 15, 3600));
                    case "autoUploadScanSeconds" ->
                        parsed.put(key, clampInt(key, raw, 0, 3600));
                    case "autoApproveMaxBytes" ->
                        parsed.put(key, clampLong(key, raw, 0L, 1L << 31));
                    case "maxModelBytes" ->
                        parsed.put(key, clampLong(key, raw, 0L, 1L << 31));
                    case "listMaxEntries" ->
                        parsed.put(key, clampInt(key, raw, 8, 32767));
                    case "maxPendingPerPlayer" ->
                        parsed.put(key, clampInt(key, raw, 0, 256));
                    default -> {
                        actionBar(player, "未知配置键：" + key);
                        return;
                    }
                }
            } catch (RuntimeException invalid) {
                actionBar(player, "配置值不合法：" + key + " = " + raw);
                return;
            }
        }
        for (var entry : parsed.entrySet()) {
            switch (entry.getKey()) {
                case "rejectAllUploads" -> RelayConfig.REJECT_ALL_UPLOADS.set((Boolean) entry.getValue());
                case "allowAutoUpload" -> RelayConfig.ALLOW_AUTO_UPLOAD.set((Boolean) entry.getValue());
                case "uploadMode" -> RelayConfig.setUploadMode((String) entry.getValue());
                case "autoSync" -> RelayConfig.AUTO_SYNC.set((Boolean) entry.getValue());
                case "autoSyncMinIntervalSeconds" ->
                    RelayConfig.AUTO_SYNC_MIN_INTERVAL_SECONDS.set((Integer) entry.getValue());
                case "autoUploadScanSeconds" ->
                    RelayConfig.AUTO_UPLOAD_SCAN_SECONDS.set((Integer) entry.getValue());
                case "autoApproveMaxBytes" ->
                    RelayConfig.AUTO_APPROVE_MAX_BYTES.set((Long) entry.getValue());
                case "maxModelBytes" ->
                    RelayConfig.MAX_MODEL_BYTES.set(((Long) entry.getValue()).intValue());
                case "listMaxEntries" ->
                    RelayConfig.LIST_MAX_ENTRIES.set((Integer) entry.getValue());
                case "maxPendingPerPlayer" ->
                    RelayConfig.MAX_PENDING_PER_PLAYER.set((Integer) entry.getValue());
                default -> {
                }
            }
        }
        var server = player.getServer();
        // 切到非审批档时，把队列里已有的条目按新规则处理掉。
        var newMode = RelayConfig.uploadMode();
        if (!newMode.equals("review") && parsed.containsKey("uploadMode")) {
            try {
                var summary = com.asu.ysmrelay.upload.UploadService.instance().drainPending(newMode);
                actionBar(player, summary);
            } catch (RuntimeException failure) {
                com.asu.ysmrelay.YsmRelay.LOGGER.warn("[ysm-relay] 处理待审队列失败", failure);
            }
        }
        if (server != null) {
            broadcast(server);
        }
        com.asu.ysmrelay.YsmRelay.LOGGER.info("[ysm-relay] {} 修改了分发设置：{}",
            player.getGameProfile().getName(), parsed.keySet());
    }

    /** 配置键 → [permissions] 里的选项名。 */
    private static String optionOf(String key) {
        return switch (key) {
            case "rejectAllUploads", "allowAutoUpload", "autoSync", "uploadMode",
                 "autoSyncMinIntervalSeconds", "autoUploadScanSeconds",
                 "autoApproveMaxBytes", "maxModelBytes", "listMaxEntries", "maxPendingPerPlayer" -> key;
            default -> null;
        };
    }

    /** 提示走动作栏，不刷聊天栏。 */
    private static void actionBar(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal("[ysm-relay] " + text), true);
    }

    private static boolean parseBoolean(String key, String raw) {
        var text = raw.toLowerCase(Locale.ROOT);
        if (text.equals("true")) {
            return true;
        }
        if (text.equals("false")) {
            return false;
        }
        throw new IllegalArgumentException("not a boolean: " + key);
    }

    private static long clampLong(String key, String raw, long min, long max) {
        var value = Long.parseLong(raw);
        if (value < min || value > max) {
            throw new IllegalArgumentException("out of range: " + key);
        }
        return value;
    }

    private static int clampInt(String key, String raw, int min, int max) {
        var value = Integer.parseInt(raw);
        if (value < min || value > max) {
            throw new IllegalArgumentException("out of range: " + key);
        }
        return value;
    }
}
