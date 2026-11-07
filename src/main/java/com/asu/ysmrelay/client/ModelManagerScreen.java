package com.asu.ysmrelay.client;

import com.asu.ysmrelay.net.msg.DataReply;
import com.asu.ysmrelay.net.msg.DataRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 「模型管理」目录页，两个分页：模型列表 / 拉黑名单。
 *
 * <p>模型列表一行三列（上传的玩家、模型名、大小），右侧「删除」「拉黑」；
 * 拉黑名单一行显示上传者、模型名、短哈希，右侧「解除」。
 * 名字太长按像素截断并加省略号；顶部搜索框同时过滤玩家名与模型名。
 */
public final class ModelManagerScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int PAGE_SIZE = 10;
    private static final int COL_UPLOADER = 110;
    private static final int COL_SIZE = 70;
    private static final int BUTTON_WIDTH = 46;
    private static final int BUTTON_GAP = 4;
    private static final int ACTION_WIDTH = BUTTON_WIDTH * 2 + BUTTON_GAP;

    @Nullable
    private static ModelManagerScreen current;
    @Nullable
    private final Screen parent;
    @Nullable
    private EditBox search;
    private final List<Button> rowButtons = new ArrayList<>();
    private int page;
    private int refreshIn;
    private boolean blockedView;

    public ModelManagerScreen(@Nullable Screen parent) {
        super(Component.translatable("screen.ysm_relay.manage.title"));
        this.parent = parent;
    }

    static void onData() {
        var screen = current;
        if (screen != null && Minecraft.getInstance().screen == screen) {
            screen.rebuildRows();
        }
    }

    @Override
    protected void init() {
        current = this;
        ClientLists.request(kind());
        search = new EditBox(font, width / 2 - 230, 40, 200, 20,
            Component.translatable("screen.ysm_relay.manage.search"));
        search.setResponder(text -> {
            page = 0;
            rebuildRows();
        });
        addRenderableWidget(search);
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.manage.tab_models"), button -> switchView(false))
            .bounds(width / 2 - 24, 40, 76, 20).build());
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.manage.tab_blocked"), button -> switchView(true))
            .bounds(width / 2 + 56, 40, 76, 20).build());
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.settings.refresh"),
                button -> ClientLists.request(kind()))
            .bounds(width / 2 + 138, 40, 46, 20).build());
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.upload.back"), button -> onClose())
            .bounds(width / 2 + 190, 40, 40, 20).build());
        addRenderableWidget(Button.builder(Component.literal("<"), button -> {
            if (page > 0) {
                page--;
                rebuildRows();
            }
        }).bounds(width / 2 + 30, height - 30, 28, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> {
            if ((page + 1) * PAGE_SIZE < filtered().size()) {
                page++;
                rebuildRows();
            }
        }).bounds(width / 2 + 62, height - 30, 28, 20).build());
        rebuildRows();
    }

    private int kind() {
        return blockedView ? DataRequest.KIND_BLOCKED : DataRequest.KIND_MANAGED;
    }

    private void switchView(boolean blocked) {
        blockedView = blocked;
        page = 0;
        ClientLists.request(kind());
        rebuildRows();
    }

    private List<DataReply.Entry> filtered() {
        var all = blockedView ? ClientLists.blocked() : ClientLists.managed();
        var query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        var result = new ArrayList<DataReply.Entry>(all.size());
        for (var entry : all) {
            var uploader = entry.extra() == null ? "" : entry.extra();
            var name = entry.fileName() == null ? "" : entry.fileName();
            if (query.isEmpty()
                || name.toLowerCase(Locale.ROOT).contains(query)
                || uploader.toLowerCase(Locale.ROOT).contains(query)) {
                result.add(entry);
            }
        }
        return result;
    }

    private static String uploaderOf(DataReply.Entry entry) {
        return entry.extra() == null || entry.extra().isBlank()
            ? Component.translatable("screen.ysm_relay.manage.local").getString()
            : entry.extra();
    }

    private void rebuildRows() {
        for (var button : rowButtons) {
            removeWidget(button);
        }
        rowButtons.clear();
        var entries = filtered();
        var maxPage = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= maxPage) {
            page = maxPage - 1;
        }
        var start = page * PAGE_SIZE;
        for (var index = start; index < Math.min(entries.size(), start + PAGE_SIZE); index++) {
            var entry = entries.get(index);
            var rowY = 70 + (index - start) * ROW_HEIGHT;
            var id = entry.modelId();
            var name = entry.fileName() == null ? "" : entry.fileName();
            if (blockedView) {
                rowButtons.add(addRenderableWidget(Button
                    .builder(Component.translatable("screen.ysm_relay.manage.unblock"),
                        clicked -> confirm(id, name, DeleteConfirmScreen.Mode.UNBLOCK))
                    .bounds(width / 2 + 240 - BUTTON_WIDTH * 2, rowY, BUTTON_WIDTH * 2, 20).build()));
            } else {
                var blockX = width / 2 + 240 - BUTTON_WIDTH;
                var deleteX = blockX - BUTTON_GAP - BUTTON_WIDTH;
                rowButtons.add(addRenderableWidget(Button
                    .builder(Component.translatable("screen.ysm_relay.manage.delete"),
                        clicked -> confirm(id, name, DeleteConfirmScreen.Mode.DELETE))
                    .bounds(deleteX, rowY, BUTTON_WIDTH, 20).build()));
                rowButtons.add(addRenderableWidget(Button
                    .builder(Component.translatable("screen.ysm_relay.manage.block"),
                        clicked -> confirm(id, name, DeleteConfirmScreen.Mode.DELETE_BLOCK))
                    .bounds(blockX, rowY, BUTTON_WIDTH, 20).build()));
            }
        }
    }

    private void confirm(String modelId, String modelName, DeleteConfirmScreen.Mode mode) {
        var minecraft = Minecraft.getInstance();
        if (mode == DeleteConfirmScreen.Mode.UNBLOCK
            || (mode == DeleteConfirmScreen.Mode.DELETE && ClientPaths.skipDeleteConfirm())
            || (mode == DeleteConfirmScreen.Mode.DELETE_BLOCK && ClientPaths.skipDeleteConfirm())) {
            var command = switch (mode) {
                case DELETE -> "ysmrelay delete " + modelId + " confirm";
                case DELETE_BLOCK -> "ysmrelay deleteblock " + modelId + " confirm";
                case UNBLOCK -> "ysmrelay unblock " + modelId;
            };
            if (minecraft.getConnection() != null) {
                minecraft.getConnection().sendCommand(command);
            }
            refreshIn = 30;
            return;
        }
        minecraft.setScreen(new DeleteConfirmScreen(this, modelId, modelName, mode));
    }

    @Override
    public void tick() {
        super.tick();
        if (refreshIn > 0 && --refreshIn == 0) {
            ClientLists.request(kind());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 18, 0xFFFFFF);
        var hint = Component.translatable(blockedView
            ? "screen.ysm_relay.manage.blocked_columns" : "screen.ysm_relay.manage.columns").getString();
        graphics.drawString(font, hint, width / 2 - 230, 62, 0x808080, false);
        super.render(graphics, mouseX, mouseY, partialTick);

        var entries = filtered();
        var start = page * PAGE_SIZE;
        for (var index = start; index < Math.min(entries.size(), start + PAGE_SIZE); index++) {
            var entry = entries.get(index);
            var rowY = 70 + (index - start) * ROW_HEIGHT;
            var textY = rowY + 6;
            var left = width / 2 - 230;
            graphics.drawString(font, trim(uploaderOf(entry), COL_UPLOADER), left, textY, 0xA0A0A0, false);
            var nameLeft = left + COL_UPLOADER;
            var nameWidth = 440 - COL_UPLOADER - COL_SIZE - ACTION_WIDTH - BUTTON_GAP;
            graphics.drawString(font, trim(entry.fileName() == null ? "" : entry.fileName(), nameWidth),
                nameLeft, textY, 0xFFFFFF, false);
            var lastColumn = blockedView
                ? "#" + shortId(entry.modelId())
                : formatSize(entry.size());
            graphics.drawString(font, lastColumn,
                width / 2 + 240 - ACTION_WIDTH - BUTTON_GAP - font.width(lastColumn), textY, 0xB0B0B0, false);
        }
        if (entries.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable(blockedView
                    ? "screen.ysm_relay.manage.blocked_empty" : "screen.ysm_relay.manage.empty"),
                width / 2, 90, 0x909090);
        }
        graphics.drawString(font, Component.literal((page + 1) + " / "
                + Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE)).getString(),
            width / 2 + 98, height - 24, 0x808080, false);
    }

    private static String shortId(String modelId) {
        return modelId == null || modelId.length() < 8 ? "" : modelId.substring(0, 8);
    }

    private String trim(String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        var ellipsis = "…";
        var builder = new StringBuilder();
        for (var index = 0; index < text.length(); index++) {
            var next = builder.toString() + text.charAt(index) + ellipsis;
            if (font.width(next) > maxWidth) {
                break;
            }
            builder.append(text.charAt(index));
        }
        return builder + ellipsis;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "-";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
