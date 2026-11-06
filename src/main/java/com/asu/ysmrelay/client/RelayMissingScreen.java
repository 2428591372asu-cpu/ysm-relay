package com.asu.ysmrelay.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 进了一个没装 relay 的服务端时点入口看到的提示。
 *
 * <p>判定方式不是查服务端的 mod 列表——客户端拿不到那份列表的可靠视图，
 * 而是进服后主动要一次配置快照，超过约 5 秒没回应就认定服务端没有本 mod。
 */
public final class RelayMissingScreen extends Screen {
    @Nullable
    private final Screen parent;

    public RelayMissingScreen(@Nullable Screen parent) {
        super(Component.translatable("screen.ysm_relay.missing.title"));
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
        graphics.drawCenteredString(font, Component.translatable("screen.ysm_relay.missing.body"),
            width / 2, height / 2 - 6, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        ScreenNavigator.backTo(parent);
    }
}
