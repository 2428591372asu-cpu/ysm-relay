package com.asu.ysmrelay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「返回上一级」的统一实现。
 *
 * <h2>为什么不直接 {@code setScreen(parent)}</h2>
 *
 * <p>YSM 自己的 {@code ModelScreen} 带一个 {@code lifetime}（绑定当前 ClientLevel）。
 * 它在 {@code ScreenEvent.Opening} 里做守卫：新屏幕的 {@code lifetime} 已经 retired 时
 * <b>直接 {@code setCanceled(true)}</b>，{@code Minecraft#setScreen} 于是什么都不做。
 *
 * <p>而我们一打开自己的界面，Minecraft 就会对旧屏幕调 {@code removed()}，YSM 在那个方法里把
 * {@code lifetime} 退掉。结果就是：**拿旧实例当 parent 返回，会被 YSM 静默拒掉** ——
 * 「点了取消没反应，ESC 也没反应」，因为两条路最终都走同一个 {@code setScreen(parent)}。
 *
 * <p>所以返回 YSM 的模型界面时必须**新建一个实例**（无参构造，原版就是这么开的），
 * 新实例的 lifetime 是干净的，守卫放行。
 */
public final class ScreenNavigator {
    private static final Logger LOGGER = LoggerFactory.getLogger("ysm_relay");
    private static final String PLAYER_MODEL_SCREEN = "com.elfmcys.ysm.client.gui.PlayerModelScreen";
    private static final String MODEL_SCREEN = "com.elfmcys.ysm.client.gui.ModelScreen";

    private ScreenNavigator() {}

    /** 返回 {@code parent}；对 YSM 模型界面做「换新实例」处理。 */
    public static void backTo(@Nullable Screen parent) {
        var minecraft = Minecraft.getInstance();
        if (parent == null) {
            minecraft.setScreen(null);
            return;
        }
        if (isYsmModelScreen(parent)) {
            var fresh = newPlayerModelScreen();
            if (fresh != null) {
                minecraft.setScreen(fresh);
                return;
            }
            // 拿不到新实例（YSM 改了类名/构造），退回游戏画面，总比卡死在提示页好。
            LOGGER.warn("[ysm-relay] 无法重建 YSM 模型界面，直接关闭");
            minecraft.setScreen(null);
            return;
        }
        minecraft.setScreen(parent);
        // 兜底：万一这次切换也被别的模组取消，退回游戏画面，不能把玩家卡在界面里。
        if (minecraft.screen != parent) {
            LOGGER.warn("[ysm-relay] 返回上一级被取消，直接关闭界面");
            minecraft.setScreen(null);
        }
    }

    /** 是不是 YSM 的模型界面体系（{@code ModelScreen} 及其子类）。这些实例都不能复用。 */
    private static boolean isYsmModelScreen(Screen screen) {
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            if (MODEL_SCREEN.equals(type.getName()) || PLAYER_MODEL_SCREEN.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Screen newPlayerModelScreen() {
        try {
            var type = Class.forName(PLAYER_MODEL_SCREEN);
            var instance = type.getConstructor().newInstance();
            return instance instanceof Screen screen ? screen : null;
        } catch (ReflectiveOperationException | LinkageError failure) {
            return null;
        }
    }
}
