package com.asu.ysmrelay.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 服务端把玩家设为 0 级（禁用 relay）后，点入口看到的提示页。
 *
 * <p>只做展示，不提供任何操作；「返回」回到打开它的界面。
 */
public final class RelayDeniedScreen extends Screen {
    @Nullable
    private final Screen parent;

    public RelayDeniedScreen(@Nullable Screen parent) {
        super(Component.translatable("screen.ysm_relay.denied.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.upload.back"), button -> onClose())
            .bounds(width / 2 - 60, height / 2 + 24, 120, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 28, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("screen.ysm_relay.denied.body"),
            width / 2, height / 2 - 6, 0xFF8080);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        ScreenNavigator.backTo(parent);
    }
}
