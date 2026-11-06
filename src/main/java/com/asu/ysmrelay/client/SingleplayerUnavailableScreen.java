package com.asu.ysmrelay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 单人模式（未开 LAN）时点进「模型分发设置」看到的页面。
 *
 * <p>没有 LAN 就没有服务端、也没有其他玩家，上传通道无处可连。明确告诉玩家而不是给一个点了没反应的面板。
 */
public final class SingleplayerUnavailableScreen extends Screen {
    private final Screen parent;

    public SingleplayerUnavailableScreen(@Nullable Screen parent) {
        super(Component.translatable("screen.ysm_relay.unavailable.title"));
        this.parent = parent;
    }

    /** 当前是不是「单人且没开 LAN」。开了 LAN 的主机就是服务端，功能可用。 */
    public static boolean isActive() {
        var minecraft = Minecraft.getInstance();
        var server = minecraft.getSingleplayerServer();
        return server != null && !server.isPublished();
    }

    @Override
    protected void init() {
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.unavailable.back"), button -> onClose())
            .bounds(width / 2 - 60, height / 2 + 24, 120, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 28, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("screen.ysm_relay.unavailable.body"),
            width / 2, height / 2 - 6, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        ScreenNavigator.backTo(parent);
    }
}
