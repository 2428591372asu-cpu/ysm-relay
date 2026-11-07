package com.asu.ysmrelay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 删除 / 删除并拉黑 / 解除拉黑的确认页。
 *
 * <p>三者的差异只在标题、正文、要发的命令；「不再提示」只对删除类生效。
 */
public final class DeleteConfirmScreen extends Screen {
    public enum Mode { DELETE, DELETE_BLOCK, UNBLOCK }

    @Nullable
    private final Screen parent;
    private final String modelId;
    private final String modelName;
    private final Mode mode;
    private boolean skipNext;

    public DeleteConfirmScreen(@Nullable Screen parent, String modelId, String modelName, Mode mode) {
        super(Component.translatable(titleKey(mode)));
        this.parent = parent;
        this.modelId = modelId;
        this.modelName = modelName == null ? "" : modelName;
        this.mode = mode;
        this.skipNext = mode != Mode.UNBLOCK && ClientPaths.skipDeleteConfirm();
    }

    private static String titleKey(Mode mode) {
        return switch (mode) {
            case DELETE -> "screen.ysm_relay.manage.confirm_title";
            case DELETE_BLOCK -> "screen.ysm_relay.manage.block_title";
            case UNBLOCK -> "screen.ysm_relay.manage.unblock_title";
        };
    }

    private static String bodyKey(Mode mode) {
        return switch (mode) {
            case DELETE -> "screen.ysm_relay.manage.confirm_body";
            case DELETE_BLOCK -> "screen.ysm_relay.manage.block_body";
            case UNBLOCK -> "screen.ysm_relay.manage.unblock_body";
        };
    }

    @Override
    protected void init() {
        var centerY = height / 2;
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.manage.confirm_yes"), clicked -> confirm())
            .bounds(width / 2 - 80, centerY, 70, 20).build());
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.upload.back"), clicked -> cancel())
            .bounds(width / 2 + 10, centerY, 70, 20).build());
        if (mode != Mode.UNBLOCK) {
            addRenderableWidget(Button
                .builder(skipLabel(), button -> {
                    skipNext = !skipNext;
                    button.setMessage(skipLabel());
                })
                .bounds(width / 2 - 100, centerY + 36, 200, 20).build());
        }
    }

    private Component skipLabel() {
        return Component.literal(skipNext ? "[x] " : "[ ] ")
            .append(Component.translatable("screen.ysm_relay.manage.confirm_skip"));
    }

    private void confirm() {
        var minecraft = Minecraft.getInstance();
        var command = switch (mode) {
            case DELETE -> "ysmrelay delete " + modelId + " confirm";
            case DELETE_BLOCK -> "ysmrelay deleteblock " + modelId + " confirm";
            case UNBLOCK -> "ysmrelay unblock " + modelId;
        };
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().sendCommand(command);
        }
        refreshParent();
        minecraft.setScreen(parent);
    }

    private void cancel() {
        Minecraft.getInstance().setScreen(parent);
    }

    private void refreshParent() {
        if (mode == Mode.UNBLOCK) {
            ClientLists.request(com.asu.ysmrelay.net.msg.DataRequest.KIND_BLOCKED);
        } else {
            ClientLists.request(com.asu.ysmrelay.net.msg.DataRequest.KIND_MANAGED);
        }
    }

    @Override
    public void onClose() {
        cancel();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 60, 0xFFFFFF);
        var color = mode == Mode.DELETE ? 0xA0A0A0 : 0xFF8080;
        graphics.drawCenteredString(font, Component.translatable(bodyKey(mode), modelName),
            width / 2, height / 2 - 24, color);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
