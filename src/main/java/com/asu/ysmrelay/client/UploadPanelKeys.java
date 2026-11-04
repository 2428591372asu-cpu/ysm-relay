package com.asu.ysmrelay.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 上传面板的快捷键。默认 {@code U}，可在原版「按键绑定」里改。
 *
 * <p>只在客户端注册：{@code Dist.CLIENT} 让整个类在服务端不会被加载，{@code KeyMapping} 也就不会
 * 出现在专用服务器的类初始化路径上。
 */
@Mod.EventBusSubscriber(modid = "ysm_relay", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class UploadPanelKeys {
    public static final KeyMapping OPEN_PANEL = new KeyMapping(
        "key.ysm_relay.open_panel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, "key.categories.ysm_relay");

    private UploadPanelKeys() {}

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_PANEL);
    }
}
