package com.asu.ysmrelay.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 客户端上传面板：把「去文件夹里找模型，再敲一条命令」换成「点一下」。
 *
 * <p>列表只做一件事——把本机模型根下面的候选文件列出来。点一行切换选中，点「上传」把
 * **所有选中项**交给 {@link ClientUploads} 排队，进度直接读它暴露的 volatile 字段 +
 * {@link ClientUploads#outcomeOf} 的逐文件结局，不额外维护第二套状态。
 *
 * <p>选中用 {@link Path} 而不是行号：重扫会重排列表，行号会漂，路径不会。
 *
 * <p>刻意不用 {@code ObjectSelectionList}：它在这一版里要额外处理 hover、旁白与滚动继承，
 * 而这里只需要「一行一个文件、能多选、能滚」。手写滚动比继承一整棵控件树更小、更可控。
 */
public final class UploadPanelScreen extends Screen {
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_ROWS = 500;
    private static final int SCAN_DEPTH = 6;
    private static final List<String> EXTENSIONS = List.of(".mxc", ".ysm", ".zip", ".7z");

    private final List<LocalModel> models = new ArrayList<>();
    /** 选中的文件路径。用 LinkedHashSet 保持点击顺序，上传顺序与玩家点击顺序一致。 */
    private final Set<Path> selected = new LinkedHashSet<>();
    /** 搜索过滤后的可见列表。 */
    private final List<LocalModel> view = new ArrayList<>();
    private int page;
    @Nullable
    private EditBox searchBox;

    /** 已经通过 YSM 解析器验证的文件。 */
    private static final Set<Path> verified = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 预检没通过的文件 → 原因。只作为提示，不再禁止上传（最终由服务端判定）。 */
    private static final java.util.Map<Path, String> rejected =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean validating;

    private int listLeft;
    private int listRight;
    private int listTop;
    private int listBottom;
    private int scroll;

    /** 上一级界面；从 YSM 模型界面进来时是一个真实屏幕，从快捷键进来时为 null。 */
    @Nullable
    private final Screen parent;

    @Nullable
    private Button uploadButton;

    @Nullable
    private Button cancelButton;
    @Nullable
    private Button selectAllButton;

    public UploadPanelScreen() {
        this(null);
    }

    public UploadPanelScreen(@Nullable Screen parent) {
        super(Component.translatable("screen.ysm_relay.upload.title"));
        this.parent = parent;
    }

    @Override
    public void onClose() {
        // 「取消」和 ESC 都按返回上一级处理，而不是直接回到游戏。
        var minecraft = Minecraft.getInstance();
        ScreenNavigator.backTo(parent);
    }

    @Override
    protected void init() {
        listLeft = width / 2 - 190;
        listRight = width / 2 + 190;
        listTop = 84;
        listBottom = height - 78;

        models.clear();
        models.addAll(scan(ClientPaths.manualFolder()));
        // 选中项按路径保留，重开界面时清掉已经不在列表里的
        selected.removeIf(path -> models.stream().noneMatch(model -> model.path().equals(path)));

        searchBox = new EditBox(font, listLeft, 58, listRight - listLeft - 80, 18,
            Component.translatable("screen.ysm_relay.upload.search"));
        searchBox.setResponder(text -> applyFilter());
        addRenderableWidget(searchBox);
        addRenderableWidget(Button.builder(Component.translatable("screen.ysm_relay.upload.choose_folder"),
                button -> Minecraft.getInstance().setScreen(new UploadFolderScreen(this)))
            .bounds(listRight - 160, 34, 72, 16).build());
        addRenderableWidget(Button.builder(Component.literal("<"), button -> turnPage(-1))
            .bounds(listRight - 74, 58, 22, 18).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> turnPage(1))
            .bounds(listRight - 48, 58, 22, 18).build());
        applyFilter();

        var footer = height - 56;
        selectAllButton = addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.upload.all"), button -> toggleSelectAll())
            .bounds(width / 2 - 190, footer, 72, 20).build());
        uploadButton = addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.upload.upload"), button -> uploadSelected())
            .bounds(width / 2 - 114, footer, 72, 20).build());
        cancelButton = addRenderableWidget(Button.builder(Component.translatable("screen.ysm_relay.upload.cancel"),
            button -> {
                if (ClientUploads.busy()) {
                    ClientUploads.cancel();
                } else {
                    onClose();
                }
            }).bounds(width / 2 - 38, footer, 72, 20).build());
        addRenderableWidget(Button
            .builder(Component.translatable("screen.ysm_relay.upload.rescan"), button -> rescan())
            .bounds(width / 2 + 38, footer, 72, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.ysm_relay.upload.folder"), button ->
                ClientFiles.openFolder(ClientPaths.manualFolder()))
            .bounds(width / 2 + 114, footer, 76, 20).build());
        // 常驻返回键：上传进行中 cancelButton 会变成「中断」，那时候仍需要一个纯粹的返回。
        addRenderableWidget(Button.builder(Component.translatable("screen.ysm_relay.upload.back"),
                button -> onClose())
            .bounds(width / 2 + 114, height - 30, 76, 20).build());

        validateAsync();
        refreshButtons();
    }

    /**
     * 后台预检：只给提示，不做硬性拦截。
     *
     * <p>早先的版本把解析失败直接标成「不支持的模型」并禁止勾选，结果把大量正常模型
     * （尤其编译过的 .ysm 容器）挡在外面——解析入口和 YSM 真正导入的路径并不相同，
     * 失败也不一定代表文件坏。现在失败只记原因，收不收由服务端说了算。
     */
    private void validateAsync() {
        if (validating) {
            return;
        }
        var pendingList = new ArrayList<Path>();
        for (var model : models) {
            if (!verified.contains(model.path()) && !rejected.containsKey(model.path())) {
                pendingList.add(model.path());
            }
        }
        if (pendingList.isEmpty()) {
            return;
        }
        validating = true;
        var thread = new Thread(() -> {
            try {
                for (var path : pendingList) {
                    try {
                        com.asu.ysmrelay.upload.ModelIdentity.of(path);
                        verified.add(path);
                    } catch (IOException | RuntimeException invalid) {
                        rejected.put(path, invalid.getMessage() == null
                            ? invalid.getClass().getSimpleName() : invalid.getMessage());
                    }
                }
            } finally {
                validating = false;
                Minecraft.getInstance().execute(this::refreshButtons);
            }
        }, "ysm-relay-validate");
        thread.setDaemon(true);
        thread.start();
    }

    /** 按搜索框内容重建可见列表，并把页码拉回第一页。 */
    private void applyFilter() {
        var query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        view.clear();
        for (var model : models) {
            if (query.isEmpty()
                || model.relative().toLowerCase(Locale.ROOT).contains(query)) {
                view.add(model);
            }
        }
        page = 0;
        scroll = 0;
        refreshButtons();
    }

    /** 翻页：一次一屏。 */
    private void turnPage(int delta) {
        var visible = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        var maxPage = Math.max(0, (view.size() - 1) / visible);
        var next = Math.max(0, Math.min(maxPage, page + delta));
        if (next != page) {
            page = next;
            scroll = page * visible;
        }
    }

    /** 重新扫描本机模型根。只换数据，不重建控件——重建会改正在被点击遍历的 renderables。 */
    private void rescan() {
        // 上一轮的预检结论作废：文件可能刚下载完，也可能上一轮是环境问题。
        verified.clear();
        rejected.clear();
        models.clear();
        models.addAll(scan(ClientPaths.manualFolder()));
        selected.removeIf(path -> models.stream().noneMatch(model -> model.path().equals(path)));
        scroll = 0;
        applyFilter();
        validateAsync();
        refreshButtons();
    }

    /**
     * 刷新两个按钮的可用状态与文案。
     *
     * <p>上传按钮的判据是 **{@code !busy()} 且非空**。历史上这里写反成 {@code busy() || ...}，
     * 结果「正在传的时候按钮还能点」，点了只弹一句「已有上传在进行中」。
     */
    private void refreshButtons() {
        if (uploadButton != null) {
            uploadButton.active = !ClientUploads.busy() && !selected.isEmpty();
        }
        if (cancelButton != null) {
            // 有上传在跑时它是「中断」，否则是「返回上一级」。
            cancelButton.setMessage(Component.translatable(ClientUploads.busy()
                ? "screen.ysm_relay.upload.stop"
                : "screen.ysm_relay.upload.back"));
        }
        if (selectAllButton != null) {
            var selectable = view.size();
            var allSelected = selectable > 0 && selected.size() >= selectable;
            selectAllButton.setMessage(Component.translatable(
                allSelected ? "screen.ysm_relay.upload.none" : "screen.ysm_relay.upload.all"));
            selectAllButton.active = selectable > 0;
        }
    }

    /** 全选 / 全不选。已经有没选中的就全选，否则清空。 */
    private void toggleSelectAll() {
        var selectable = new ArrayList<Path>();
        for (var model : view) {
            selectable.add(model.path());
        }
        var allSelected = !selectable.isEmpty() && selected.size() >= selectable.size();
        if (allSelected) {
            selected.clear();
        } else {
            selected.addAll(selectable);
        }
        refreshButtons();
    }

    private void uploadSelected() {
        if (selected.isEmpty()) {
            return;
        }
        // 按列表顺序取，不用 selected 的插入顺序 —— 玩家看到的是列表，上传顺序应当跟列表一致
        var files = new ArrayList<Path>();
        for (var model : view) {
            if (selected.contains(model.path())) {
                files.add(model.path());
            }
        }
        if (files.isEmpty()) {
            return;
        }
        var problem = ClientUploads.enqueue(files);
        if (problem != null && Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(
                Component.literal("[ysm-relay] " + problem).withStyle(ChatFormatting.RED), false);
        }
        refreshButtons();
    }

    @Override
    public void tick() {
        super.tick();
        refreshButtons();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        var root = ClientPaths.manualFolder().toString();
        var shown = root.length() <= 62 ? root : "…" + root.substring(root.length() - 61);
        graphics.drawString(font, Component.literal(shown).withStyle(ChatFormatting.DARK_GRAY),
            width / 2 - 190, 34, 0xAAAAAA, false);
        graphics.drawString(font,
            Component.translatable("screen.ysm_relay.upload.count", view.size()),
            width / 2 - 190, 46, 0xCCCCCC, false);
        if (!selected.isEmpty()) {
            var picked = Component.translatable("screen.ysm_relay.upload.selected", selected.size())
                .withStyle(ChatFormatting.AQUA);
            graphics.drawString(font, picked, listRight - font.width(picked), 46, 0x55FFFF, false);
        }

        graphics.fill(listLeft - 2, listTop - 2, listRight + 2, listBottom + 2, 0x50000000);

        if (view.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.ysm_relay.upload.empty"),
                (listLeft + listRight) / 2, (listTop + listBottom) / 2, 0xAAAAAA);
        } else {
            renderRows(graphics, mouseX, mouseY);
        }

        renderProgress(graphics);
    }

    private void renderRows(GuiGraphics graphics, int mouseX, int mouseY) {
        var visible = (listBottom - listTop) / ROW_HEIGHT;
        var last = Math.min(view.size(), scroll + visible);
        graphics.enableScissor(listLeft, listTop, listRight, listBottom);
        for (var index = scroll; index < last; index++) {
            var rowY = listTop + (index - scroll) * ROW_HEIGHT;
            var model = view.get(index);
            var hovered = mouseX >= listLeft && mouseX <= listRight && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

            if (selected.contains(model.path())) {
                graphics.fill(listLeft, rowY, listRight, rowY + ROW_HEIGHT - 2, 0x8040A0FF);
            } else if (hovered) {
                graphics.fill(listLeft, rowY, listRight, rowY + ROW_HEIGHT - 2, 0x40FFFFFF);
            }

            graphics.drawString(font, selected.contains(model.path()) ? "▣" : "▢", listLeft + 5, rowY + 7,
                0xFFFFFF, false);
            var bad = rejected.containsKey(model.path());
            graphics.drawString(font, bad ? model.label() + "  ⚠ 预检未通过（仍可上传）" : model.label(),
                listLeft + 20, rowY + 7, bad ? 0xFFD27F : 0xFFFFFF, false);

            var size = Component.literal(formatSize(model.size())).withStyle(ChatFormatting.GRAY);
            graphics.drawString(font, size, listRight - 6 - font.width(size), rowY + 7, 0xAAAAAA, false);

            var state = statusMark(model.path());
            if (state != null) {
                var x = listRight - 12 - font.width(size);
                graphics.drawString(font, state, x, rowY + 7, 0xFFFFFF, false);
            }
        }
        graphics.disableScissor();

        var maxPage = Math.max(1, (view.size() + visible - 1) / visible);
        var hint = (page + 1) + " / " + maxPage + "   (" + view.size() + ")";
        graphics.drawString(font, hint, listRight - font.width(hint), listBottom + 4, 0x888888, false);
    }

    /** 每行的状态位：这一批里这个文件的结局。没参与本批返回 null，不画。 */
    @Nullable
    private static Component statusMark(Path path) {
        var outcome = ClientUploads.outcomeOf(path);
        if (outcome == null) {
            return null;
        }
        return switch (outcome) {
            case QUEUED -> Component.literal("·").withStyle(ChatFormatting.GRAY);
            case RUNNING -> Component.literal("↑").withStyle(ChatFormatting.AQUA);
            case COMMITTED -> Component.literal("✓").withStyle(ChatFormatting.GREEN);
            case ALREADY -> Component.literal("=").withStyle(ChatFormatting.DARK_AQUA);
            case AWAITING -> Component.literal("?").withStyle(ChatFormatting.YELLOW);
            case REJECTED -> Component.literal("✗").withStyle(ChatFormatting.RED);
            case FAILED -> Component.literal("!").withStyle(ChatFormatting.DARK_RED);
            case CANCELLED -> Component.literal("-").withStyle(ChatFormatting.DARK_GRAY);
        };
    }

    private void renderProgress(GuiGraphics graphics) {
        var line = ClientUploads.statusLine();
        var phase = ClientUploads.phase();
        if (phase == ClientUploads.Phase.IDLE && line.isEmpty()) {
            return;
        }
        var barLeft = width / 2 - 190;
        var barRight = width / 2 + 190;
        var barTop = height - 30;
        graphics.fill(barLeft, barTop, barRight, barTop + 6, 0x60000000);
        var fraction = Math.max(0.0F, Math.min(1.0F, ClientUploads.progress()));
        graphics.fill(barLeft, barTop, barLeft + (int)((barRight - barLeft) * fraction), barTop + 6, 0xFF40A0FF);

        var queued = ClientUploads.queuedCount();
        var tail = queued > 0 && ClientUploads.busy() ? "  （队列还剩 " + queued + " 个）" : "";
        graphics.drawString(font, Component.literal(phase.name() + "  " + line + tail), barLeft, barTop - 11,
            0xCCCCCC, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= listLeft && mouseX <= listRight && mouseY >= listTop && mouseY < listBottom) {
            var index = scroll + (int)((mouseY - listTop) / ROW_HEIGHT);
            if (index >= 0 && index < view.size()) {
                var path = view.get(index).path();

                if (!selected.remove(path)) {
                    selected.add(path);
                }
                refreshButtons();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        turnPage(delta > 0 ? -1 : 1);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 客户端自己的模型根下的候选文件。读目录失败按空列表处理，不打断界面。 */
    private static List<LocalModel> scan(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        var found = new ArrayList<LocalModel>();
        try (Stream<Path> stream = Files.walk(root, SCAN_DEPTH)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                if (found.size() >= MAX_ROWS) {
                    return;
                }
                if (!looksLikeModel(path)) {
                    return;
                }
                try {
                    found.add(new LocalModel(path, root.relativize(path).toString(), Files.size(path)));
                } catch (IOException ignored) {
                    // 单个文件读不到大小就跳过，不影响整份列表。
                }
            });
        } catch (IOException | RuntimeException failure) {
            return List.of();
        }
        found.sort(Comparator.comparing(LocalModel::relative));
        return found;
    }

    private static boolean looksLikeModel(Path path) {
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (var extension : EXTENSIONS) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    /** 列表里的一行。 */
    private record LocalModel(Path path, String relative, long size) {
        String label() {
            return relative;
        }
    }
}
