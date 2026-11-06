package com.asu.ysmrelay;

import com.asu.ysmrelay.upload.UploadService;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 服务端事件挂载：玩家掉线时放弃未完成的上传。
 *
 * <p>不做这件事的后果是：staging 文件一直留着，而且该玩家的 {@code byPlayer} 槽位不释放，
 * 他重连后再传会被判 BUSY。
 */
@Mod.EventBusSubscriber(modid = YsmRelay.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RelayServerEvents {
    private RelayServerEvents() {}

    /** 启动时把三个关键目录打进日志：确认上传到底会落到哪，比事后猜快得多。 */
    @SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        try {
            YsmRelay.LOGGER.info("[ysm-relay] 上传根={}", com.asu.ysmrelay.upload.RelayPaths.uploadRoot());
            YsmRelay.LOGGER.info("[ysm-relay] 授权根={}", com.asu.ysmrelay.upload.RelayPaths.authRoot());
            YsmRelay.LOGGER.info("[ysm-relay] 暂存根={} 待审根={}",
                com.asu.ysmrelay.upload.RelayPaths.stagingRoot(),
                com.asu.ysmrelay.upload.RelayPaths.pendingRoot());
            YsmRelay.LOGGER.info("[ysm-relay] targetRoot={}",
                com.asu.ysmrelay.config.RelayConfig.targetAuthRoot() ? "auth" : "custom");
        } catch (RuntimeException failure) {
            YsmRelay.LOGGER.warn("[ysm-relay] 启动时读取目录失败", failure);
        }
    }

    /** 玩家进服就下发一份分发设置快照，客户端据此决定哪些按钮可用。 */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            com.asu.ysmrelay.config.RelayConfigSync.send(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            UploadService.instance().onDisconnect(player);
        }
    }
}
