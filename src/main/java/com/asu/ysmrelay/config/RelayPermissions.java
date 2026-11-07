package com.asu.ysmrelay.config;

import java.util.Locale;
import net.minecraft.server.level.ServerPlayer;

/**
 * 三层权限的唯一判定点。
 *
 * <p>三级：1 = 所有玩家；2 = 模型管理员（op / 两个组 / 手动授予）；3 = 与服主同权
 * （手动授予）。服主身份来自控制台或 LAN 主机，不依赖配置。
 *
 * <p><b>op 自动算 2 级</b>（{@link #OP_LEVEL} 及以上），被撤销 op 后自然失效；
 * 判定每次现算，不落副本。
 */
public final class RelayPermissions {
    /** op 门槛：2 及以上自动算作组内成员。 */
    public static final int OP_LEVEL = 2;

    private RelayPermissions() {}

    /** 是不是 op（自动组内成员）。 */
    public static boolean isOp(ServerPlayer player) {
        return player != null && player.hasPermissions(OP_LEVEL);
    }

    /**
     * 能不能处理上传请求（审批）。
     *
     * <p>2 级是「模型管理员」：审批与模型管理都在这一级，不再拆成两个更细的组。
     * 门槛本身可配（approveUploads），改配置就能抬高。
     */
    public static boolean canApprove(ServerPlayer player) {
        return player != null && effectiveLevel(player) >= RelayConfig.levelOf("approveUploads");
    }

    /** 能不能管理模型（使用权、grant/revoke/move/delete/名单）。 */
    public static boolean canManage(ServerPlayer player) {
        return player != null && effectiveLevel(player) >= RelayConfig.levelOf("manageRights");
    }

    /** 这个玩家还能不能使用本模组（0 级 = 被禁用）。 */
    public static boolean canUseMod(ServerPlayer player) {
        return player != null && effectiveLevel(player) > 0;
    }

    /** 能不能分发等级（授予/撤销 0–3 级）。只有 3 级自己和服主可以做。 */
    public static boolean canDistributeLevels(ServerPlayer player) {
        return player != null && effectiveLevel(player) >= 3;
    }

    /**
     * 能不能改配置。
     *
     * <p>3 级与服主同权：被授予 3 级就等于拿到服主这一档的全部能力，
     * 包括修改权限定义类配置。
     */
    public static boolean canConfigure(ServerPlayer player) {
        return player != null && effectiveLevel(player) >= 3;
    }

    /** 有效等级：普通玩家 1，权限组/op/白名单 2，服主 3；手动授予可以往上加。 */
    public static int effectiveLevel(ServerPlayer player) {
        var level = 1;
        // 注意：上传白名单只影响「能不能传」，不该把人抬到 2 级。
        // 早先这里带上了 isUploadWhitelisted，统一等级判定后会让白名单玩家顺带拿到
        // 管理/审批权限——那是越权，已经去掉了。
        if (isOp(player) || inGroup(player, RelayConfig.approveGroup())
            || inGroup(player, RelayConfig.manageGroup())) {
            level = 2;
        }
        // 只有「单人开 LAN 的主机」天然 3 级；专用服务器上 3 级必须由控制台手动授予。
        if (isHost(player)) {
            level = 3;
        }
        var granted = RelayConfig.grantedLevel(uuidOf(player));
        if (granted == 0) {
            // 显式授予 0 级 = 禁用本模组的全部功能，覆盖 op / 组 / 房主推断。
            return 0;
        }
        return Math.max(level, granted);
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
