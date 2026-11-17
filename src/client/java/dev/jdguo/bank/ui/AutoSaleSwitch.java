package dev.jdguo.bank.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Binary switch. The server snapshot remains the only source of confirmed state. */
final class AutoSaleSwitch extends ButtonWidget {
    private final boolean enabled;

    AutoSaleSwitch(int x, int y, int width, boolean enabled, Runnable submit) {
        super(x, y, width, 22, Text.literal("自动待售：" + (enabled ? "开" : "关")), button -> {
            button.active = false; // Avoid duplicate requests until the authoritative snapshot arrives.
            submit.run();
        }, DEFAULT_NARRATION_SUPPLIER);
        this.enabled = enabled;
    }

    @Override
    public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), right = x + getWidth();
        context.fill(x, y, right, y + getHeight(), isHovered() && active ? 0xFF343C48 : 0xFF232933);
        int track = right - 43;
        context.fill(track, y + 4, right - 5, y + 18, active ? (enabled ? 0xFF318868 : 0xFF686868) : 0xFF444444);
        int knob = enabled ? right - 18 : track + 2;
        context.fill(knob, y + 6, knob + 11, y + 16, active ? 0xFFF3F3F3 : 0xFF999999);
        context.drawText(MinecraftClient.getInstance().textRenderer, getMessage(), x + 6, y + 7,
                active ? 0xFFFFFFFF : 0xFF999999, false);
    }
}
