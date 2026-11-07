package com.asu.ysmrelay.client;

import com.asu.ysmrelay.net.msg.DataReply;
import com.asu.ysmrelay.net.msg.DataRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 两张列表面的共同实现：我的上传、待审队列。
 *
 * <p>带搜索框与分页：搜索按文件名和模型哈希前缀匹配，分页固定每页 8 行。
 * 动作统一走命令，服务端在命令里做权限复核。
 */
public final class ListsScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private static final int PAGE_SIZE = 8;

    @Nullable
    private static ListsScreen current;

    @Nullable
    private final Screen parent;
    private final int kind;
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();

    @Nullable
    private EditBox search;
    private int page;

    public ListsScreen(@Nullable Screen parent, int kind) {
        super(Component.translatable(kind == DataRequest.KIND_MINE
            ? "screen.ysm_relay.list.mine_title"
            : kind == DataRequest.KIND_MY_PENDING
                ? "screen.ysm_relay.list.my_pending_title"
                : "screen.ysm_relay.list.pending_title"));
        this.parent = parent;
        this.kind = kind;
    }

    static void onData() {
        var screen = current;
        if (screen != null) {
            screen.rebuildRows();
        }
    }

    @Override
    protected void init() {
        current = this;
        search = new EditBox(font, width / 2 - 210, 48, 280, 20,
            Component.translatable("screen.ysm_relay.list.search"));
        search.setResponder(text -> rebuildRows());
        addRenderableWidget(search);
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.settings.refresh"), button -> ClientLists.request(kind))
            .bounds(width / 2 + 76, 48, 70, 20).build());
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.upload.back"), button -> onClose())
            .bounds(width / 2 + 150, 48, 60, 20).build());

        addRenderableWidget(Button
            .builder(Component.literal("<"), button -> {
                if (page > 0) {
                    page--;
                    rebuildRows();
                }
            })
            .bounds(width / 2 + 56, height - 32, 30, 20).build());
        addRenderableWidget(Button
            .builder(Component.literal(">"), button -> {
                if ((page + 1) * PAGE_SIZE < filtered().size()) {
                    page++;
                    rebuildRows();
                }
            })
            .bounds(width / 2 + 90, height - 32, 30, 20).build());

        ClientLists.request(kind);
        rebuildRows();
    }

    private List<DataReply.Entry> filtered() {
        var all = kind == DataRequest.KIND_MINE ? ClientLists.mine()
            : kind == DataRequest.KIND_MY_PENDING ? ClientLists.myPending() : ClientLists.pending();
        var query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return all;
        }
        var result = new ArrayList<DataReply.Entry>();
        for (var entry : all) {
            if (entry.fileName().toLowerCase(Locale.ROOT).contains(query)
                || entry.modelId().toLowerCase(Locale.ROOT).startsWith(query)) {
                result.add(entry);
            }
        }
        return result;
    }

    /** 执行完命令后隔几 tick 重新拉一次列表，避免界面停留在旧数据。 */
    private int refreshIn;

    @Override
    public void tick() {
        super.tick();
        if (refreshIn > 0 && --refreshIn == 0) {
            ClientLists.request(kind);
        }
    }

    private void rebuildRows() {
        for (var widget : rowWidgets) {
            removeWidget(widget);
        }
        rowWidgets.clear();
        var entries = filtered();
        var maxPage = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= maxPage) {
            page = maxPage - 1;
        }
        var start = page * PAGE_SIZE;
        for (var index = start; index < Math.min(entries.size(), start + PAGE_SIZE); index++) {
            var entry = entries.get(index);
            var row = 78 + (index - start) * ROW_HEIGHT;
            var label = Component.literal(shortName(entry.fileName()) + "  " + miB(entry.size())
                + "  " + entry.modelId().substring(0, 8));
            var info = addRenderableWidget(Button.builder(label, button -> {
            }).bounds(width / 2 - 210, row, 260, 20).build());
            info.active = false;
            rowWidgets.add(info);

            if (kind == DataRequest.KIND_MINE) {
                var delete = addRenderableWidget(Button
                    .builder(Component.translatable("screen.ysm_relay.list.delete"), button ->
                        command("relayme delete " + entry.modelId() + " confirm"))
                    .bounds(width / 2 + 56, row, 70, 20).build());
                rowWidgets.add(delete);
            } else if (kind == DataRequest.KIND_MY_PENDING) {
                var cancel = addRenderableWidget(Button
                    .builder(Component.translatable("screen.ysm_relay.list.cancel"), button ->
                        command("relayme cancel " + entry.extra()))
                    .bounds(width / 2 + 56, row, 70, 20).build());
                rowWidgets.add(cancel);
            } else {
                var approve = addRenderableWidget(Button
                    .builder(Component.translatable("screen.ysm_relay.list.approve"), button ->
                        command("ysmrelay approve " + entry.extra()))
                    .bounds(width / 2 + 56, row, 70, 20).build());
                rowWidgets.add(approve);
                var reject = addRenderableWidget(Button
                    .builder(Component.translatable("screen.ysm_relay.list.reject"), button ->
                        command("ysmrelay reject " + entry.extra()))
                    .bounds(width / 2 + 130, row, 70, 20).build());
                rowWidgets.add(reject);
            }
        }
    }

    private void command(String text) {
        // 命令是异步执行的，等服务端处理完再拉列表。
        refreshIn = 15;
        sendCommand(text);
    }

    private static void sendCommand(String text) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.connection != null) {
            player.connection.sendCommand(text);
        }
    }

    private static String shortName(String name) {
        return name.length() <= 24 ? name : name.substring(0, 21) + "...";
    }

    private static String miB(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 26, 0xFFFFFF);
        var entries = filtered();
        if (entries.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.ysm_relay.list.empty"),
                width / 2, height / 2 - 20, 0xA0A0A0);
        }
        var maxPage = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        graphics.drawString(font, Component.literal((page + 1) + " / " + maxPage + "   (" + entries.size() + ")"),
            width / 2 - 210, height - 26, 0xAAAAAA, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        current = null;
        ScreenNavigator.backTo(parent);
    }
}
