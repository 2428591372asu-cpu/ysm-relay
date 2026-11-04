package com.asu.ysmrelay.admin;

import com.asu.ysmrelay.YsmRelay;
import com.asu.ysmrelay.ysm.YsmBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 把 {@link AuthAdmin} 的授权矩阵落进玩家的运行时 grant 集合。
 *
 * <p>时机很关键：YSM 在玩家完成协议握手时（{@code SessionProtocolHandler.acceptSession}）会自己
 * 读一次 {@code AuthModelsCapability} 并同步给客户端。因此我们只要在握手之前把 capability 写对，
 * 就不需要抢在 YSM 之后重推。{@code PlayerLoggedInEvent} 早于那次握手。
 *
 * <p>这里刻意不在 tick 里轮询：矩阵变化由命令路径主动 {@code applyAll}，登录由本类补一次，
 * 没有第三条会改变授权的事实来源。
 */
@Mod.EventBusSubscriber(modid = YsmRelay.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RelayAuthEvents {
    private RelayAuthEvents() {}

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!YsmRelay.ysmCompatible()) {
            return;
        }
        var server = player.getServer();
        if (server == null) {
            return;
        }
        // 切到服务端线程：登录事件本身已经在服务端线程，这里只是把异常隔离在单个玩家范围内。
        server.execute(() -> {
            try {
                AuthAdmin.get().applyTo(player);
            } catch (RuntimeException failure) {
                YsmRelay.LOGGER.error("[ysm-relay] 为 {} 应用模型授权矩阵失败", player.getGameProfile().getName(),
                    failure);
            }
        });
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        // 死亡/换维度克隆时 Forge 会复制 capability；矩阵本身没变，只需要让新实体重新对齐一次。
        if (!(event.getEntity() instanceof ServerPlayer player) || !YsmRelay.ysmCompatible()) {
            return;
        }
        var server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            try {
                AuthAdmin.get().applyTo(player);
                YsmBridge.refreshGrants(player);
            } catch (RuntimeException failure) {
                YsmRelay.LOGGER.error("[ysm-relay] 克隆后刷新 {} 的模型授权失败",
                    player.getGameProfile().getName(), failure);
            }
        });
    }
}
