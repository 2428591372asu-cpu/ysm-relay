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
 * 「模型管理」目录页。
 *
 * <p>一行一个模型，三列：上传的玩家、模型名、大小。名字太长按像素截断并加省略号。
 * 顶部搜索框同时过滤玩家名与模型名；底部翻页与刷新。
 *
 * <p>删除是「两次点击确认」：第一次点变成「确认」，5 秒内再点才真的发命令。
 */
public final class ModelManagerScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int PAGE_SIZE = 10;
    private static final long CONFIRM_WINDOW_MS = 5000L;
    private static final int COL_UPLOADER = 110;
    private static final int COL_SIZE = 78;
    private static final int DELETE_WIDTH = 46;

    @Nullable
    private static ModelManagerScreen current;
    @Nullable
    private final Screen parent;
    @Nullable
    private EditBox search;
    private final List<Button> rowButtons = new ArrayList<>();
    private int page;
    private int refreshIn;

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
        ClientLists.request(DataRequest.KIND_MANAGED);
        search = new EditBox(font, width / 2 - 230, 40, 250, 20,
            Component.translatable("screen.ysm_relay.manage.search"));
        search.setResponder(text -> {
            page = 0;
            rebuildRows();
        });
        addRenderableWidget(search);
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.settings.refresh"),
                button -> ClientLists.request(DataRequest.KIND_MANAGED))
            .bounds(width / 2 + 30, 40, 80, 20).build());
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.upload.back"), button -> onClose())
            .bounds(width / 2 + 116, 40, 60, 20).build());
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

    private List<DataReply.Entry> filtered() {
        var all = ClientLists.managed();
        var query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        var result = new ArrayList<DataReply.Entry>(all.size());
        for (var entry : all) {
            var uploader = uploaderOf(entry);
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
            var boundsX = width / 2 + 240 - DELETE_WIDTH;
            var button = addRenderableWidget(Button
                .builder(Component.translatable("screen.ysm_relay.manage.delete"),
                    clicked -> onDelete(id, name))
                .bounds(boundsX, rowY, DELETE_WIDTH, 20).build());
            rowButtons.add(button);
        }
    }

    private void onDelete(String modelId, String modelName) {
        if (!ClientPaths.skipDeleteConfirm()) {
            Minecraft.getInstance().setScreen(new DeleteConfirmScreen(this, modelId, modelName));
            return;
        }
        var minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().sendCommand("ysmrelay delete " + modelId + " confirm");
        }
        refreshIn = 30;
    }

    @Override
    public void tick() {
        super.tick();
        if (refreshIn > 0 && --refreshIn == 0) {
            ClientLists.request(DataRequest.KIND_MANAGED);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 18, 0xFFFFFF);
        var hint = Component.translatable("screen.ysm_relay.manage.columns").getString();
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
            var nameWidth = 440 - COL_UPLOADER - COL_SIZE;
            graphics.drawString(font, trim(entry.fileName() == null ? "" : entry.fileName(), nameWidth),
                nameLeft, textY, 0xFFFFFF, false);
            var sizeText = formatSize(entry.size());
            graphics.drawString(font, sizeText,
                width / 2 + 240 - DELETE_WIDTH - 6 - font.width(sizeText), textY, 0xB0B0B0, false);
        }
        if (entries.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.ysm_relay.manage.empty"),
                width / 2, 90, 0x909090);
        }
        graphics.drawString(font, Component.literal((page + 1) + " / "
                + Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE)).getString(),
            width / 2 + 98, height - 24, 0x808080, false);
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
