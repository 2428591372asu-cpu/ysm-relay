package com.asu.ysmrelay.client;

import java.nio.file.Path;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** 设置自动上传目录。 */
public final class AutoUploadFolderScreen extends Screen {
    @Nullable
    private final Screen parent;
    @Nullable
    private EditBox input;

    public AutoUploadFolderScreen(@Nullable Screen parent) {
        super(Component.translatable("screen.ysm_relay.auto_folder.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        input = new EditBox(font, width / 2 - 160, height / 2 - 20, 320, 20,
            Component.translatable("screen.ysm_relay.auto_folder.hint"));
        input.setMaxLength(512);
        var current = ClientAutoUpload.folder();
        input.setValue(current == null ? "" : current.toAbsolutePath().toString());
        addRenderableWidget(input);

        var row = height / 2 + 12;
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.auto_folder.save"), button -> {
                var raw = input == null ? "" : input.getValue().trim();
                try {
                    ClientAutoUpload.setFolder(raw.isEmpty() ? null : Path.of(raw));
                } catch (RuntimeException ignored) {
                    // 非法路径按清除处理
                    ClientAutoUpload.setFolder(null);
                }
                onClose();
            })
            .bounds(width / 2 - 152, row, 96, 20).build());
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.auto_folder.open"), button -> {
                var configured = ClientAutoUpload.folder();
                if (configured != null) {
                    ClientFiles.openFolder(configured);
                }
            })
            .bounds(width / 2 - 48, row, 96, 20).build());
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.auto_folder.clear"), button -> {
                ClientAutoUpload.setFolder(null);
                if (input != null) {
                    input.setValue("");
                }
            })
            .bounds(width / 2 + 56, row, 96, 20).build());
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.upload.back"), button -> onClose())
            .bounds(width / 2 - 48, row + 26, 96, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 56, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("screen.ysm_relay.auto_folder.body"),
            width / 2, height / 2 - 40, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        ScreenNavigator.backTo(parent);
    }
}
