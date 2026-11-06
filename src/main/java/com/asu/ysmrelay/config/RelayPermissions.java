package com.asu.ysmrelay.config;

import java.util.Locale;
import net.minecraft.server.level.ServerPlayer;

/**
 * 三层权限的唯一判定点。
 *
 * <p>服主 = 原版权限等级 4（单人 LAN 主机天然是 4，专用服的服主也是 4）。
 *
 * <p>审批与使用权两处由「玩家组」决定：组是服务端保存的 UUID 名单。
 * <b>op 自动算作组内成员</b>（{@link #OP_LEVEL} 及以上），被撤销 op 后自然失效，
 * 不需要额外同步——判定是每次现算的，不落副本。
 */
public final class RelayPermissions {
    /** 服主门槛：原版最高权限等级。 */
    public static final int OWNER_LEVEL = 4;
    /** op 门槛：2 及以上自动算作组内成员。 */
    public static final int OP_LEVEL = 2;

    private RelayPermissions() {}

    /** 是不是服主。 */
    public static boolean isOwner(ServerPlayer player) {
        return player != null && player.hasPermissions(OWNER_LEVEL);
    }

    /** 是不是 op（自动组内成员）。 */
    public static boolean isOp(ServerPlayer player) {
        return player != null && player.hasPermissions(OP_LEVEL);
    }

    /** 能不能处理上传请求（审批）。 */
    public static boolean canApprove(ServerPlayer player) {
        return isOp(player) || inGroup(player, RelayConfig.approveGroup());
    }

    /** 能不能管理模型使用权（grant / revoke / move / delete）。 */
    public static boolean canManage(ServerPlayer player) {
        return isOp(player) || inGroup(player, RelayConfig.manageGroup());
    }

    /** 只有服主能改审批模式等「策略开关」。 */
    public static boolean canConfigure(ServerPlayer player) {
        return isOwner(player);
    }

    /** 有效等级：普通玩家 1，权限组/op/白名单 2，服主 3；手动授予可以往上加。 */
    public static int effectiveLevel(ServerPlayer player) {
        var level = 1;
        if (isOp(player) || inGroup(player, RelayConfig.approveGroup())
            || inGroup(player, RelayConfig.manageGroup()) || isUploadWhitelisted(player)) {
            level = 2;
        }
        // 只有「单人开 LAN 的主机」天然 3 级；专用服务器上 3 级必须由控制台手动授予。
        if (isHost(player)) {
            level = 3;
        }
        return Math.max(level, RelayConfig.grantedLevel(uuidOf(player)));
    }

    /** 这个玩家是不是「这台机器的房主」（单人开 LAN 的主机）。 */
    public static boolean isHost(ServerPlayer player) {
        var server = player.getServer();
        if (server == null || !server.isSingleplayer()) {
            return false;
        }
        try {
            var profile = server.getSingleplayerProfile();
            return profile != null && profile.getId() != null && profile.getId().equals(player.getUUID());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** 是否在上传白名单里。 */
    public static boolean isUploadWhitelisted(ServerPlayer player) {
        return RelayConfig.uploadWhitelist().contains(uuidOf(player));
    }

    /** 能不能拉黑某个玩家的上传（服主或任一权限组成员）。 */
    public static boolean canBlockUploader(ServerPlayer player) {
        return canManage(player) || canApprove(player);
    }

    /** 玩家自己的上传，只有本人能删。 */
    public static boolean canDeleteOwn(ServerPlayer player, String uploaderUuid) {
        return player != null && uploaderUuid != null && uploaderUuid.equalsIgnoreCase(uuidOf(player));
    }

    public static String uuidOf(ServerPlayer player) {
        return player.getUUID().toString().toLowerCase(Locale.ROOT);
    }

    private static boolean inGroup(ServerPlayer player, java.util.Set<String> group) {
        return player != null && !group.isEmpty() && group.contains(uuidOf(player));
    }
}
