package com.asu.ysmrelay.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 把快捷键翻译成「打开上传面板」。
 *
 * <p>快捷键状态只能在客户端 tick 里消费：{@code consumeClick()} 攒的是 GLFW 回调写下的计数，
 * 必须回到主线程再 setScreen。
 */
@Mod.EventBusSubscriber(modid = "ysm_relay", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class UploadPanelOpener {
    private UploadPanelOpener() {}

    /** 进服时重置「服务端有没有 relay」的判定，并主动问一次快照。 */
    @SubscribeEvent
    public static void onLoggingIn(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        ClientSettings.clear();
        ClientLists.clear();
        ClientSettings.request();
    }

    /** 断开连接时清掉快照，避免把上一个服的设置带到下一个服。 */
    @SubscribeEvent
    public static void onLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSettings.clear();
        ClientLists.clear();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        var minecraft = Minecraft.getInstance();
        ClientSettings.noteTick(minecraft);
        while (UploadPanelKeys.OPEN_PANEL.consumeClick()) {
            // 没进世界之前没有模型根可扫，也不该弹面板。
            if (minecraft.player == null) {
                continue;
            }
            if (ClientSettings.disabledByServer()) {
                // 0 级仍可进入功能页，只是除「我的上传」外都置灰。
                minecraft.setScreen(new SettingsScreen(null));
                continue;
            }
            if (ClientSettings.isRelayMissing()) {
                minecraft.setScreen(new RelayMissingScreen(null));
                continue;
            }
            if (SingleplayerUnavailableScreen.isActive()) {
                minecraft.setScreen(new SingleplayerUnavailableScreen(null));
                continue;
            }
            minecraft.setScreen(new UploadPanelScreen());
        }
    }
}
