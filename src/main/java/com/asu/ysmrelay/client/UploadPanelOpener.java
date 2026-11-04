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

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        var minecraft = Minecraft.getInstance();
        while (UploadPanelKeys.OPEN_PANEL.consumeClick()) {
            // 没进世界之前没有模型根可扫，也不该弹面板。
            if (minecraft.player == null) {
                continue;
            }
            minecraft.setScreen(new UploadPanelScreen());
        }
    }
}
