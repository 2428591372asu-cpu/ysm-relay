package com.asu.ysmrelay.client;

import com.asu.ysmrelay.net.msg.DataRequest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** 删除模型前的确认框：确认 / 取消 / 不再提示。 */
public final class DeleteConfirmScreen extends Screen {
    @Nullable
    private final Screen parent;
    private final String modelId;
    private final String modelName;
    private boolean skipNext;

    public DeleteConfirmScreen(@Nullable Screen parent, String modelId, String modelName) {
        super(Component.translatable("screen.ysm_relay.manage.confirm_title"));
        this.parent = parent;
        this.modelId = modelId;
        this.modelName = modelName == null ? "" : modelName;
        this.skipNext = ClientPaths.skipDeleteConfirm();
    }

    @Override
    protected void init() {
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.manage.confirm_yes"), button -> {
                ClientPaths.setSkipDeleteConfirm(skipNext);
                var minecraft = Minecraft.getInstance();
                if (minecraft.getConnection() != null) {
                    minecraft.getConnection().sendCommand("ysmrelay delete " + modelId + " confirm");
                }
                ClientLists.request(DataRequest.KIND_MANAGED);
                minecraft.setScreen(parent);
            })
            .bounds(width / 2 - 100, height / 2 + 10, 96, 20).build());
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.manage.confirm_no"),
                button -> Minecraft.getInstance().setScreen(parent))
            .bounds(width / 2 + 4, height / 2 + 10, 96, 20).build());
        addRenderableWidget(Button
            .builder(skipLabel(), button -> {
                skipNext = !skipNext;
                button.setMessage(skipLabel());
            })
            .bounds(width / 2 - 100, height / 2 + 44, 200, 20).build());
    }

    private Component skipLabel() {
        var mark = skipNext ? "[x] " : "[ ] ";
        return Component.literal(mark)
            .append(Component.translatable("screen.ysm_relay.manage.confirm_skip"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 46, 0xFFFFFF);
        graphics.drawCenteredString(font,
            Component.translatable("screen.ysm_relay.manage.confirm_body", modelName),
            width / 2, height / 2 - 24, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
