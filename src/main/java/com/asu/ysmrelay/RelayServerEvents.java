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

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            UploadService.instance().onDisconnect(player);
        }
    }
}
