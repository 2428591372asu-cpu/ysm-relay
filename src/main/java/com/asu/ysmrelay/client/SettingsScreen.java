package com.asu.ysmrelay.client;

import com.asu.ysmrelay.net.RelayNetwork;
import com.asu.ysmrelay.net.msg.ConfigSnapshot;
import com.asu.ysmrelay.net.msg.ConfigUpdate;
import com.asu.ysmrelay.net.msg.DataRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 「模型分发设置」：三栏竖向滚动。
 *
 * <p>栏内按所需等级从低到高排：1 级（客户端项）在最上，其次是 3 级的服务端项。
 * 等级不足只置灰，不隐藏。滚动条与滚轮行为对齐原版按键设置。
 */
public final class SettingsScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_HEIGHT = 18;
    private static final int GAP = 4;
    private static final long MIB = 1024L * 1024L;
    private static final int[] LIST_STEPS = {50, 100, 200, 400, 800, 1200, 1600, 2400,
        3200, 4096, 6144, 8192, 12288, 16384, 24576, 32767};
    private static final String[] MODES = {"review", "auto", "full"};

    @Nullable
    private static SettingsScreen current;
    @Nullable
    private final Screen parent;

    private final List<Entry> entries = new ArrayList<>();
    private final List<Header> headers = new ArrayList<>();
    private int scroll;
    private int contentHeight;
    private int viewTop;
    private int viewBottom;

    private record Entry(AbstractWidget widget, int baseY, boolean editable) {
    }

    private record Header(String key, int baseY) {
    }

    public SettingsScreen(@Nullable Screen parent) {
        super(Component.translatable("screen.ysm_relay.settings.title"));
        this.parent = parent;
    }

    static void fromSnapshot() {
        var screen = current;
        if (screen != null && Minecraft.getInstance().screen == screen) {
            screen.rebuildAll();
        }
    }

    @Override
    protected void init() {
        current = this;
        viewTop = 40;
        viewBottom = height - 22;
        ClientSettings.request();
        rebuildAll();
    }

    /** 重建控件时保留滚动位置：否则每次提交配置（服务端回快照）都会跳回顶部。 */
    private void rebuildAll() {
        var keepScroll = scroll;
        clearWidgets();
        entries.clear();
        headers.clear();
        // 顶部角落两个常驻按钮：放在视口之外，既不会被 clearWidgets 抹掉，也不会被裁剪区裁掉。
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.settings.refresh"), button -> ClientSettings.request())
            .bounds(8, 6, 80, 18).build());
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.upload.back"), button -> onClose())
            .bounds(width - 88, 6, 80, 18).build());

        var snapshot = ClientSettings.current().orElse(null);
        if (snapshot == null) {
            addRenderableWidget(Button.builder(Component.translatable("screen.ysm_relay.settings.loading"),
                button -> ClientSettings.request()).bounds(width / 2 - 120, viewTop + 10, 240, 20).build());
            return;
        }
        var x = width / 2 - 150;
        var width = 300;
        var y = 0;

        y = header("screen.ysm_relay.section.upload", y);
        y = clientRow(snapshot, "upload", x, width, y, () ->
            Minecraft.getInstance().setScreen(new UploadPanelScreen(this)));
        y = clientRow(snapshot, "mine", x, width, y, () ->
            Minecraft.getInstance().setScreen(new ListsScreen(this, DataRequest.KIND_MINE)));
        y = clientRow(snapshot, "my_pending", x, width, y, () ->
            Minecraft.getInstance().setScreen(new ListsScreen(this, DataRequest.KIND_MY_PENDING)));
        y = toggle(snapshot, "allowAutoUpload", "screen.ysm_relay.settings.allow_auto_upload", x, width, y);
        y = scanSlider(snapshot, x, width, y);
        y = folderRow(snapshot, x, width, y);

        y = header("screen.ysm_relay.section.review", y);
        y = modeRow(snapshot, x, width, y);
        y = approveCapSlider(snapshot, x, width, y);
        y = clientRow(snapshot, "pending", x, width, y, () ->
            Minecraft.getInstance().setScreen(new ListsScreen(this, DataRequest.KIND_PENDING)));

        y = header("screen.ysm_relay.section.sync", y);
        y = toggle(snapshot, "autoSync", "screen.ysm_relay.settings.auto_sync", x, width, y);
        y = syncSlider(snapshot, x, width, y);
        y = toggle(snapshot, "rejectAllUploads", "screen.ysm_relay.settings.reject_all", x, width, y);
        y = listSlider(snapshot, x, width, y);
        y = pendingPerPlayerSlider(snapshot, x, width, y);
        y = modelSlider(snapshot, x, width, y);

        contentHeight = y;
        var viewport = viewBottom - viewTop;
        scroll = Math.max(0, Math.min(Math.max(0, contentHeight - viewport), keepScroll));
        layout();
    }

    private int header(String key, int y) {
        headers.add(new Header(key, y));
        return y + HEADER_HEIGHT;
    }

    private void add(AbstractWidget widget, int baseY, boolean editable) {
        addRenderableWidget(widget);
        entries.add(new Entry(widget, baseY, editable));
    }

    private int toggle(ConfigSnapshot snapshot, String option, String labelKey, int x, int width, int y) {
        var value = option.equals("autoSync") ? snapshot.autoSync
            : option.equals("allowAutoUpload") ? snapshot.allowAutoUpload : snapshot.rejectAllUploads;
        var label = Component.translatable(labelKey).copy()
            .append(Component.literal(": "))
            .append(Component.translatable(value ? "screen.ysm_relay.settings.on" : "screen.ysm_relay.settings.off")
                .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        var button = Button.builder(label, clicked -> send(option, String.valueOf(!value)))
            .bounds(x, 0, width, 20).build();
        add(button, y, snapshot.mayEdit(option));
        return y + ROW_HEIGHT;
    }

    private int clientRow(ConfigSnapshot snapshot, String id, int x, int width, int y, Runnable action) {
        var label = switch (id) {
            case "upload" -> "screen.ysm_relay.settings.open_upload";
            case "mine" -> "screen.ysm_relay.settings.my_uploads";
            case "my_pending" -> "screen.ysm_relay.settings.my_pending";
            default -> "screen.ysm_relay.settings.pending";
        };
        var button = Button.builder(Component.translatable(label), clicked -> action.run())
            .bounds(x, 0, width, 20).build();
        var host = snapshot.host;
        var enabled = switch (id) {
            case "upload", "mine" -> !host;
            case "pending" -> snapshot.canApprove || snapshot.mayEdit("approveUploads");
            default -> true;
        };
        // 全自动模式下待审队列恒空，直接置灰避免误解。
        // 只有「服主的待审队列」随模式置灰；「我的待审」必须一直可用——
        // 自动同意模式下超上限的上传同样会进队列，玩家得能撤回。
        if (id.equals("pending")) {
            enabled = enabled && snapshot.uploadMode == 0;
        }
        add(button, y, enabled);
        return y + ROW_HEIGHT;
    }

    private int folderRow(ConfigSnapshot snapshot, int x, int width, int y) {
        var button = Button.builder(Component.translatable("screen.ysm_relay.settings.auto_upload_dir"),
            clicked -> Minecraft.getInstance().setScreen(new AutoUploadFolderScreen(this)))
            .bounds(x, 0, width, 20).build();
        // 三重条件：服务端允许 + 非主机 + 当前不是审批模式。
        var enabled = snapshot.allowAutoUpload && !snapshot.host && snapshot.uploadMode != 0;
        add(button, y, enabled);
        return y + ROW_HEIGHT;
    }

    private int modeRow(ConfigSnapshot snapshot, int x, int width, int y) {
        var label = Component.translatable("screen.ysm_relay.settings.mode").copy()
            .append(Component.literal(": "))
            .append(Component.translatable(MODES[snapshot.uploadMode].equals("review")
                ? "screen.ysm_relay.mode.review"
                : MODES[snapshot.uploadMode].equals("auto")
                    ? "screen.ysm_relay.mode.auto" : "screen.ysm_relay.mode.full"));
        var next = MODES[(snapshot.uploadMode + 1) % MODES.length];
        var button = Button.builder(label, clicked -> send("uploadMode", next))
            .bounds(x, 0, width, 20).build();
        add(button, y, snapshot.mayEdit("uploadMode"));
        return y + ROW_HEIGHT;
    }

    private int syncSlider(ConfigSnapshot snapshot, int x, int width, int y) {
        add(new RelaySlider(x, 0, width, 20,
            Component.translatable("screen.ysm_relay.settings.sync_interval").getString(),
            15, 600, 5, snapshot.autoSyncMinIntervalSeconds, snapshot.mayEdit("autoSyncMinIntervalSeconds"),
            value -> value + " s", value -> send("autoSyncMinIntervalSeconds", String.valueOf(value))),
            y, true);
        return y + ROW_HEIGHT;
    }

    private int scanSlider(ConfigSnapshot snapshot, int x, int width, int y) {
        add(new RelaySlider(x, 0, width, 20,
            Component.translatable("screen.ysm_relay.settings.scan_mode").getString(),
            0, 600, 15, snapshot.autoUploadScanSeconds, snapshot.mayEdit("autoUploadScanSeconds"),
            value -> value <= 0 ? Component.translatable("screen.ysm_relay.settings.scan_watch").getString()
                : value + " s",
            value -> send("autoUploadScanSeconds", String.valueOf(value))),
            y, true);
        return y + ROW_HEIGHT;
    }

    private int approveCapSlider(ConfigSnapshot snapshot, int x, int width, int y) {
        add(new RelaySlider(x, 0, width, 20,
            Component.translatable("screen.ysm_relay.settings.approve_cap").getString(),
            0, 256, 1, (int) Math.min(Integer.MAX_VALUE, snapshot.autoApproveMaxBytes / MIB),
            snapshot.mayEdit("autoApproveMaxBytes"),
            value -> value <= 0 ? "∞" : String.format(Locale.ROOT, "%.2f MiB", (double) value),
            value -> send("autoApproveMaxBytes", String.valueOf(value * MIB))),
            y, true);
        return y + ROW_HEIGHT;
    }

    private int modelSlider(ConfigSnapshot snapshot, int x, int width, int y) {
        add(new RelaySlider(x, 0, width, 20,
            Component.translatable("screen.ysm_relay.settings.model_cap").getString(),
            0, 1024, 4, (int) Math.min(Integer.MAX_VALUE, snapshot.maxModelBytes / MIB),
            snapshot.mayEdit("maxModelBytes"),
            value -> value <= 0 ? "∞" : String.format(Locale.ROOT, "%.2f MiB", (double) value),
            value -> send("maxModelBytes", String.valueOf(value * MIB))),
            y, true);
        return y + ROW_HEIGHT;
    }

    private int listSlider(ConfigSnapshot snapshot, int x, int width, int y) {
        add(new RelaySlider(x, 0, width, 20,
            Component.translatable("screen.ysm_relay.settings.list_cap").getString(),
            0, LIST_STEPS.length - 1, 1, nearestListStep(snapshot.listMaxEntries),
            snapshot.mayEdit("listMaxEntries"),
            step -> String.valueOf(LIST_STEPS[Math.max(0, Math.min(LIST_STEPS.length - 1, step))]),
            step -> send("listMaxEntries", String.valueOf(
                LIST_STEPS[Math.max(0, Math.min(LIST_STEPS.length - 1, step))]))),
            y, true);
        return y + ROW_HEIGHT;
    }

    /** 单个玩家最多有几个待审条目；0 = 不限。 */
    private int pendingPerPlayerSlider(ConfigSnapshot snapshot, int x, int width, int y) {
        add(new RelaySlider(x, 0, width, 20,
            Component.translatable("screen.ysm_relay.settings.pending_per_player").getString(),
            0, 100, 1, Math.max(0, Math.min(100, snapshot.maxPendingPerPlayer)),
            snapshot.mayEdit("maxPendingPerPlayer"),
            value -> value <= 0 ? "∞" : String.valueOf(value),
            value -> send("maxPendingPerPlayer", String.valueOf(value))),
            y, true);
        return y + ROW_HEIGHT;
    }

    private static int nearestListStep(int value) {
        var best = 0;
        for (var index = 0; index < LIST_STEPS.length; index++) {
            if (Math.abs(LIST_STEPS[index] - value) < Math.abs(LIST_STEPS[best] - value)) {
                best = index;
            }
        }
        return best;
    }

    private void layout() {
        for (var entry : entries) {
            var y = viewTop + entry.baseY() - scroll;
            entry.widget().setY(y);
            var visible = y + 20 > viewTop && y < viewBottom;
            entry.widget().visible = visible;
            entry.widget().active = visible && entry.editable();
        }
    }

    private void scrollBy(int delta) {
        var viewport = viewBottom - viewTop;
        var max = Math.max(0, contentHeight - viewport);
        scroll = Math.max(0, Math.min(max, scroll + delta));
        layout();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollBy((int) (-delta * ROW_HEIGHT * 2));
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 18, 0xFFFFFF);

        graphics.enableScissor(0, viewTop, width, viewBottom);
        for (var header : headers) {
            var y = viewTop + header.baseY() - scroll;
            if (y + HEADER_HEIGHT > viewTop && y < viewBottom) {
                graphics.drawString(font, Component.translatable(header.key()), width / 2 - 150, y + 4,
                    0xFFD27F, false);
                graphics.fill(width / 2 - 150, y + HEADER_HEIGHT - 3, width / 2 + 150, y + HEADER_HEIGHT - 2,
                    0x60FFFFFF);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.disableScissor();

        var viewport = viewBottom - viewTop;
        if (contentHeight > viewport) {
            var barX = width / 2 + 158;
            graphics.fill(barX, viewTop, barX + 4, viewBottom, 0x40FFFFFF);
            var barHeight = Math.max(20, viewport * viewport / contentHeight);
            var barY = viewTop + (viewport - barHeight) * scroll / Math.max(1, contentHeight - viewport);
            graphics.fill(barX, barY, barX + 4, barY + barHeight, 0xFFBFBFBF);
        }

        var snapshot = ClientSettings.current().orElse(null);
        if (snapshot != null) {
            var role = snapshot.host ? "screen.ysm_relay.role.host"
                : snapshot.effectiveLevel >= 2 ? "screen.ysm_relay.role.staff" : "screen.ysm_relay.role.player";
            graphics.drawString(font,
                Component.translatable("screen.ysm_relay.settings.level", snapshot.effectiveLevel,
                    Component.translatable(role)),
                width / 2 - 150, height - 26, 0xAAAAAA, false);
        }
    }

    private void send(String key, String value) {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(key, value);
        RelayNetwork.toServer(new ConfigUpdate(changes));
    }

    @Override
    public void onClose() {
        current = null;
        ScreenNavigator.backTo(parent);
    }
}
