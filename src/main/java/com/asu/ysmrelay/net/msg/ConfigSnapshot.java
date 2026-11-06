package com.asu.ysmrelay.net.msg;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * 服务端 → 客户端：当前分发设置快照。
 *
 * <p>客户端只把它当作「界面上该显示什么」的提示，不把它当权威副本：
 * 真正的判定每次都发生在服务端，客户端即使被改包也改不动服务端行为。
 */
public final class ConfigSnapshot {
    public final boolean rejectAllUploads;
    public final boolean allowAutoUpload;
    public final boolean autoApprove;
    public final boolean autoSync;
    public final int autoSyncMinIntervalSeconds;
    public final int chunkBytes;
    public final long maxModelBytes;
    public final long autoApproveMaxBytes;
    /** 当前玩家能不能改配置（服主）。 */
    public final boolean canConfigure;
    /** 当前玩家能不能处理上传请求。 */
    public final boolean canApprove;
    /** 当前玩家能不能管模型使用权。 */
    public final boolean canManage;
    /** 客户端自动上传目录的扫描方式：0 = 文件监听，>0 = 轮询秒数。 */
    public final int autoUploadScanSeconds;
    /** 当前玩家的有效等级：1 普通、2 权限组/op、3 服主。 */
    public final int effectiveLevel;
    /** 当前玩家是不是主机端（单人开 LAN 的房主）。 */
    public final boolean host;
    /** 每个选项需要的等级，键与配置项同名。 */
    public final java.util.Map<String, Integer> optionLevels;
    /** 列表一次最多回传多少条。 */
    public final int listMaxEntries;
    /** 单个玩家最多能有几条待审；0 = 不限。 */
    public final int maxPendingPerPlayer;
    /** 上传模式：0 审批、1 自动同意、2 全自动。 */
    public final int uploadMode;

    public ConfigSnapshot(boolean rejectAllUploads, boolean allowAutoUpload, boolean autoApprove, boolean autoSync,
        int autoSyncMinIntervalSeconds, int chunkBytes, long maxModelBytes, long autoApproveMaxBytes,
        boolean canConfigure, boolean canApprove, boolean canManage, int autoUploadScanSeconds,
        int effectiveLevel, boolean host, java.util.Map<String, Integer> optionLevels, int listMaxEntries,
        int maxPendingPerPlayer, int uploadMode) {
        this.rejectAllUploads = rejectAllUploads;
        this.allowAutoUpload = allowAutoUpload;
        this.autoApprove = autoApprove;
        this.autoSync = autoSync;
        this.autoSyncMinIntervalSeconds = autoSyncMinIntervalSeconds;
        this.chunkBytes = chunkBytes;
        this.maxModelBytes = maxModelBytes;
        this.autoApproveMaxBytes = autoApproveMaxBytes;
        this.canConfigure = canConfigure;
        this.canApprove = canApprove;
        this.canManage = canManage;
        this.autoUploadScanSeconds = autoUploadScanSeconds;
        this.effectiveLevel = effectiveLevel;
        this.host = host;
        this.optionLevels = optionLevels == null ? java.util.Map.of() : java.util.Map.copyOf(optionLevels);
        this.listMaxEntries = listMaxEntries;
        this.maxPendingPerPlayer = maxPendingPerPlayer;
        this.uploadMode = uploadMode;
    }

    /** 某个选项需要的等级；没登记按 3 处理（只有服主）。 */
    public int requiredLevel(String option) {
        return optionLevels.getOrDefault(option, 3);
    }

    /** 当前玩家能不能改这一项。 */
    public boolean mayEdit(String option) {
        return effectiveLevel >= requiredLevel(option);
    }

    public static void encode(ConfigSnapshot message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.rejectAllUploads);
        buffer.writeBoolean(message.allowAutoUpload);
        buffer.writeBoolean(message.autoApprove);
        buffer.writeBoolean(message.autoSync);
        buffer.writeVarInt(message.autoSyncMinIntervalSeconds);
        buffer.writeVarInt(message.chunkBytes);
        buffer.writeVarLong(message.maxModelBytes);
        buffer.writeVarLong(message.autoApproveMaxBytes);
        buffer.writeBoolean(message.canConfigure);
        buffer.writeBoolean(message.canApprove);
        buffer.writeBoolean(message.canManage);
        buffer.writeVarInt(message.autoUploadScanSeconds);
        buffer.writeVarInt(message.effectiveLevel);
        buffer.writeBoolean(message.host);
        buffer.writeVarInt(message.listMaxEntries);
        buffer.writeVarInt(message.maxPendingPerPlayer);
        buffer.writeVarInt(message.uploadMode);
        buffer.writeVarInt(message.optionLevels.size());
        for (var entry : message.optionLevels.entrySet()) {
            buffer.writeUtf(entry.getKey(), 64);
            buffer.writeVarInt(entry.getValue());
        }
    }

    public static ConfigSnapshot decode(FriendlyByteBuf buffer) {
        var rejectAll = buffer.readBoolean();
        var allowAutoUpload = buffer.readBoolean();
        var autoApprove = buffer.readBoolean();
        var autoSync = buffer.readBoolean();
        var syncInterval = buffer.readVarInt();
        var chunkBytes = buffer.readVarInt();
        var maxModelBytes = buffer.readVarLong();
        var approveCap = buffer.readVarLong();
        var canConfigure = buffer.readBoolean();
        var canApprove = buffer.readBoolean();
        var canManage = buffer.readBoolean();
        var scanSeconds = buffer.readVarInt();
        var effectiveLevel = buffer.readVarInt();
        var host = buffer.readBoolean();
        var listMax = buffer.readVarInt();
        var pendingPerPlayer = buffer.readVarInt();
        var uploadMode = buffer.readVarInt();
        var levels = readLevels(buffer);
        return new ConfigSnapshot(rejectAll, allowAutoUpload, autoApprove, autoSync, syncInterval, chunkBytes,
            maxModelBytes, approveCap, canConfigure, canApprove, canManage, scanSeconds, effectiveLevel, host,
            levels, listMax, pendingPerPlayer, uploadMode);
    }

    private static java.util.Map<String, Integer> readLevels(FriendlyByteBuf buffer) {
        var count = buffer.readVarInt();
        if (count < 0 || count > 64) {
            throw new IllegalArgumentException("too many option levels: " + count);
        }
        var levels = new java.util.LinkedHashMap<String, Integer>();
        for (var index = 0; index < count; index++) {
            levels.put(buffer.readUtf(64), buffer.readVarInt());
        }
        return levels;
    }

    public static void handle(ConfigSnapshot message, Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        com.asu.ysmrelay.client.ClientSettings.accept(message);
    }
}
