package com.asu.ysmrelay.client;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * 给设置页用的整数滑条。
 *
 * <p>拖动过程中只改本地显示，<b>松手才发请求</b>——否则每移动一个像素就发一次配置包，
 * 服务端会收到几十条同样的更新。
 */
public final class RelaySlider extends AbstractSliderButton {
    private final String label;
    private final int min;
    private final int max;
    private final int step;
    private final IntFunction<String> format;
    private final IntConsumer onCommit;
    private final boolean editable;
    private int current;

    public RelaySlider(int x, int y, int width, int height, String label, int min, int max, int step,
        int value, boolean editable, IntFunction<String> format, IntConsumer onCommit) {
        super(x, y, width, height, Component.empty(), ratioOf(value, min, max));
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = Math.max(1, step);
        this.format = format;
        this.onCommit = onCommit;
        this.editable = editable;
        this.current = snap(value);
        this.active = editable;
        updateMessage();
    }

    private static double ratioOf(int value, int min, int max) {
        if (max <= min) {
            return 0.0;
        }
        return (double) (value - min) / (double) (max - min);
    }

    private int snap(int value) {
        var clamped = Math.max(min, Math.min(max, value));
        var steps = Math.round((float) (clamped - min) / step);
        return Math.max(min, Math.min(max, min + steps * step));
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(label + ": " + format.apply(current)));
    }

    @Override
    protected void applyValue() {
        if (!editable) {
            return;
        }
        current = snap((int) Math.round(min + value * (max - min)));
        updateMessage();
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        var handled = super.mouseReleased(mouseX, mouseY, button);
        if (handled && editable && onCommit != null) {
            onCommit.accept(current);
        }
        return handled;
    }
}
