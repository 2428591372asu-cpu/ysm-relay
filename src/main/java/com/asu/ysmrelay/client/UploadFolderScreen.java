package com.asu.ysmrelay.client;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** 设置「上传模型」面板扫描哪个目录。 */
public final class UploadFolderScreen extends Screen {
    @Nullable
    private final Screen parent;
    @Nullable
    private EditBox input;

    public UploadFolderScreen(@Nullable Screen parent) {
        super(Component.translatable("screen.ysm_relay.upload_folder.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        input = new EditBox(font, width / 2 - 160, height / 2 - 20, 320, 20,
            Component.translatable("screen.ysm_relay.upload_folder.hint"));
        input.setMaxLength(512);
        input.setValue(ClientPaths.manualFolderConfigured()
            ? ClientPaths.manualFolder().toAbsolutePath().toString() : "");
        addRenderableWidget(input);

        var row = height / 2 + 12;
        addRenderableWidget(Button.builder(Component.translatable("screen.ysm_relay.upload_folder.save"),
            button -> {
                var raw = input == null ? "" : input.getValue().trim();
                if (raw.isEmpty()) {
                    ClientPaths.setManualFolder(null);
                    onClose();
                    return;
                }
                try {
                    var path = Path.of(raw);
                    if (!Files.isDirectory(path)) {
                        if (minecraft != null && minecraft.player != null) {
                            minecraft.player.displayClientMessage(
                                Component.translatable("screen.ysm_relay.upload_folder.missing"), true);
                        }
                        return;
                    }
                    ClientPaths.setManualFolder(path);
                    onClose();
                } catch (RuntimeException invalid) {
                    ClientPaths.setManualFolder(null);
                    onClose();
                }
            }).bounds(width / 2 - 152, row, 96, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.ysm_relay.upload_folder.default"),
            button -> {
                ClientPaths.setManualFolder(null);
                if (input != null) {
                    input.setValue("");
                }
            }).bounds(width / 2 - 48, row, 96, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.ysm_relay.upload_folder.open"),
            button -> ClientFiles.openFolder(ClientPaths.manualFolder()))
            .bounds(width / 2 + 56, row, 96, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.ysm_relay.upload.back"),
            button -> onClose()).bounds(width / 2 - 48, row + 28, 96, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 56, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("screen.ysm_relay.upload_folder.body"),
            width / 2, height / 2 - 40, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
