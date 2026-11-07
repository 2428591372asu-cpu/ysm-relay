package com.asu.ysmrelay.client.gui;

import com.asu.ysmrelay.YsmRelay;
import com.asu.ysmrelay.client.ClientSettings;
import com.asu.ysmrelay.client.ClientUploads;
import com.asu.ysmrelay.client.RelayMissingScreen;
import com.asu.ysmrelay.client.SingleplayerUnavailableScreen;
import com.asu.ysmrelay.ysm.YsmBridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 把 relay 的入口按钮挂到 YSM 的玩家模型界面上。
 *
 * <p>阿苏要的是「页面挂载在 ysm 模组的页面上」「独立一个展开按钮」，玩家不该被要求记住快捷键。
 * 这里不用 mixin，也不 import 任何 {@code com.elfmcys.ysm.*} —— 判定统一走
 * {@link YsmBridge#isYsmPlayerModelScreen(Object)}。
 *
 * <h2>为什么按钮是「自己画 + 自己接点击」，而不是 {@code ScreenEvent.Init.Post#addListener}</h2>
 *
 * <p>因为 YSM 的 {@code PlayerModelScreen} 会在 4 个地方**直接调用自己的 {@code init()}**：
 * 切分类（PlayerModelScreen.java:275）、翻上一页/下一页（:285 / :292）、进入模型包（:311）。
 * 而 {@code init()} 的第一件事就是 {@code clearWidgets()} —— 它清空
 * {@code renderables} / {@code children} / {@code narratables} 三个列表。
 *
 * <p>直接调 {@code init()} <b>不会</b>再次触发 {@code ScreenEvent.Init}（Forge 只在
 * {@code rebuildWidgets()} 那条路径上发事件），所以通过 {@code addListener} 加进去的 widget
 * 会在玩家点过任意一个 YSM 自己的按钮之后集体消失。另外 {@code tick()} 在目录重建时也会调
 * {@code init()}（:470），这意味着按钮会在玩家完全没操作时随机消失 —— 这条不能交付。
 *
 * <p>自己画没有可被清空的控件列表：每帧重画、点击前置拦截，天然免疫上述时序。
 * 代价是样式只能近似 YSM 的面板（深底 + 浅色描边 + 米白字），不追求像素级一致。
 *
 * <h2>为什么画在面板下方而不是面板内部</h2>
 *
 * <p>面板 420x235（:178-179 取 {@code x = (width - 420) / 2}、{@code y = (height - 235) / 2}），
 * 底部那一行 {@code y + 215} 已经被 YSM 自己的上一页/下一页（{@code x + 198} / {@code x + 308}）、
 * 页码文字与版本号占满，挤进去必然压字。面板下方的空白区不属于任何控件，
 * 且「{@code y + 254 <= height}」对任何 {@code height >= 273} 的画布都成立，压不到屏幕外。
 */
@Mod.EventBusSubscriber(modid = YsmRelay.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RelayModelScreenButtons {
    /** 与 YSM 主面板同宽同高，取自 PlayerModelScreen.java:178-179。 */
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 235;
    /** 按钮与面板之间、按钮与按钮之间的间距。 */
    private static final int GAP = 5;
    private static final int BUTTON_WIDTH = 92;
    private static final int BUTTON_HEIGHT = 14;

    /** YSM 面板的米白底色，与 YSM 自己的按钮文字同色。 */
    private static final int TEXT_COLOR = 0xFFF3EFE0;
    private static final int BORDER_COLOR = 0xFF7A7A7A;
    private static final int BORDER_COLOR_HOVER = 0xFFF3EFE0;
    private static final int BACKGROUND_COLOR = 0xCC2A2A2A;
    private static final int BACKGROUND_COLOR_HOVER = 0xCC3C3C3C;

    private RelayModelScreenButtons() {}

    /** 画按钮。放在 {@code Render.Post}：YSM 先画完整个面板，我们在最上层补两个按钮。 */
    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!YsmBridge.isYsmPlayerModelScreen(event.getScreen())) {
            return;
        }
        var screen = event.getScreen();
        var graphics = event.getGuiGraphics();
        var left = buttonLeft(screen);
        var top = buttonTop(screen);
        var label = Component.translatable("screen.ysm_relay.model_screen.upload");
        if (ClientUploads.busy()) {
            // 面板外面也要能看出「有上传在跑」，否则玩家以为点了没反应。
            label = label.copy().append(Component.literal("  ↑"));
        }
        drawButton(graphics, left, top, label,
            isInside(event.getMouseX(), event.getMouseY(), left, top));
    }

    /**
     * 拦截落在按钮上的左键。
     *
     * <p>用 {@code .Pre} 并取消事件：按钮区域盖在 YSM 面板下沿之外，理论上不撞任何 YSM 控件，
     * 取消是为了防止将来 YSM 往这块区域加东西时「点一次触发两件事」。
     */
    @SubscribeEvent
    public static void onClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0 || !YsmBridge.isYsmPlayerModelScreen(event.getScreen())) {
            return;
        }
        var screen = event.getScreen();
        var left = buttonLeft(screen);
        var top = buttonTop(screen);
        if (!isInside((int) event.getMouseX(), (int) event.getMouseY(), left, top)) {
            return;
        }
        event.setCanceled(true);
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            // 没进世界就没有模型根可扫，弹面板只会是一片空列表。
            return;
        }
        if (ClientSettings.isRelayMissing()) {
            minecraft.setScreen(new RelayMissingScreen(event.getScreen()));
            return;
        }
        if (SingleplayerUnavailableScreen.isActive()) {
            minecraft.setScreen(new SingleplayerUnavailableScreen(event.getScreen()));
            return;
        }
        minecraft.setScreen(new com.asu.ysmrelay.client.SettingsScreen(event.getScreen()));
    }

    private static int buttonLeft(Screen screen) {
        return (screen.width - PANEL_WIDTH) / 2;
    }

    private static int buttonTop(Screen screen) {
        return (screen.height - PANEL_HEIGHT) / 2 + PANEL_HEIGHT + GAP;
    }

    private static boolean isInside(int mouseX, int mouseY, int left, int top) {
        return mouseX >= left && mouseX < left + BUTTON_WIDTH
            && mouseY >= top && mouseY < top + BUTTON_HEIGHT;
    }

    private static void drawButton(GuiGraphics graphics, int x, int y, Component label, boolean hovered) {
        var border = hovered ? BORDER_COLOR_HOVER : BORDER_COLOR;
        var background = hovered ? BACKGROUND_COLOR_HOVER : BACKGROUND_COLOR;
        graphics.fill(x - 1, y - 1, x + BUTTON_WIDTH + 1, y + BUTTON_HEIGHT + 1, border);
        graphics.fill(x, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, background);

        var font = Minecraft.getInstance().font;
        var textX = x + (BUTTON_WIDTH - font.width(label)) / 2;
        var textY = y + (BUTTON_HEIGHT - font.lineHeight) / 2 + 1;
        graphics.drawString(font, label, textX, textY, TEXT_COLOR, false);
    }
}
