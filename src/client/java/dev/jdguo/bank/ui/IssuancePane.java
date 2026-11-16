package dev.jdguo.bank.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One vertical panel for the personal-issuance body.
 * The title and the counter chrome stay outside it. Wheel and the bar scroll the body.
 */
final class IssuancePane {
    private static final int FRAME = 0xFFC8A060;

    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final Consumer<String> onToggle;
    private final BiConsumer<String, ChoiceListWidget> onPick;
    private final List<Control> controls = new ArrayList<>();
    private final List<Drawer> drawers = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private int scroll;
    private int contentHeight;
    private boolean dragging;
    private int grabOffset;

    private record Control(ClickableWidget widget, int layoutX, int layoutY) {
    }

    private record Drawer(String role, ChoiceListWidget widget, int layoutX, int layoutY, int width, int height) {
    }

    private record Line(String text, int x, int y, int color) {
    }

    IssuancePane(int x, int y, int width, int height, Consumer<String> onToggle, BiConsumer<String, ChoiceListWidget> onPick) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.onToggle = onToggle;
        this.onPick = onPick;
    }

    void addControl(ClickableWidget widget, int layoutX, int layoutY) {
        controls.add(new Control(widget, layoutX, layoutY));
    }

    void addDrawer(String role, ChoiceListWidget widget, int layoutX, int layoutY, int width, int height) {
        drawers.add(new Drawer(role, widget, layoutX, layoutY, width, height));
    }

    void addLine(String text, int lineX, int lineY, int color) {
        lines.add(new Line(text, lineX, lineY, color));
    }

    void finish(int contentBottom) {
        contentHeight = Math.max(0, contentBottom - y + 6);
        setScroll(scroll);
    }

    int scroll() {
        return scroll;
    }

    void setScroll(int next) {
        scroll = Math.max(0, Math.min(maxScroll(), next));
        apply();
    }

    boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    void render(DrawContext context, int mouseX, int mouseY, float delta) {
        apply();
        context.fill(x, y, x + width, y + height, 0xC0101014);
        int clipRight = x + width - 1;
        int clipBottom = y + height - 1;
        if (clipRight > x + 1 && clipBottom > y + 1) {
            context.enableScissor(x + 1, y + 1, clipRight, clipBottom);
            for (Line line : lines) {
                context.drawText(net.minecraft.client.MinecraftClient.getInstance().textRenderer,
                        line.text, line.x, line.y - scroll, line.color, false);
            }
            for (Drawer drawer : drawers) {
                drawer.widget.render(context, mouseX, mouseY, delta);
            }
            for (Control control : controls) {
                control.widget.render(context, mouseX, mouseY, delta);
            }
            context.disableScissor();
        }
        frame(context);
        drawBar(context);
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        apply();
        if (onBar(mouseX) && contentHeight > height) {
            int top = thumbTop();
            int thumb = thumbHeight();
            grabOffset = mouseY >= top && mouseY < top + thumb ? (int) mouseY - top : thumb / 2;
            dragging = true;
            moveThumb((int) mouseY);
            return true;
        }
        for (int i = controls.size() - 1; i >= 0; i--) {
            ClickableWidget widget = controls.get(i).widget;
            if (widget.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        for (int i = drawers.size() - 1; i >= 0; i--) {
            Drawer drawer = drawers.get(i);
            int top = drawer.layoutY - scroll;
            int visual = drawer.widget.expanded() ? drawer.height : ChoiceListWidget.HEADER;
            if (mouseX < drawer.layoutX || mouseX >= drawer.layoutX + drawer.width || mouseY < top || mouseY >= top + visual) {
                continue;
            }
            if (mouseY < top + ChoiceListWidget.HEADER) {
                onToggle.accept(drawer.role);
                return true;
            }
            if (drawer.widget.mouseClicked(mouseX, mouseY, button) && drawer.widget.pollPick()) {
                onPick.accept(drawer.role, drawer.widget);
            }
            return true;
        }
        return true;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        apply();
        for (int i = drawers.size() - 1; i >= 0; i--) {
            if (drawers.get(i).widget.mouseScrolled(mouseX, mouseY, amount)) {
                return true;
            }
        }
        setScroll(scroll - (int) Math.round(amount * 18));
        return true;
    }

    boolean mouseDragged(double mouseX, double mouseY) {
        if (!dragging) {
            return false;
        }
        moveThumb((int) mouseY);
        return true;
    }

    void mouseReleased() {
        dragging = false;
    }

    private void moveThumb(int mouseY) {
        int travel = Math.max(1, trackHeight() - thumbHeight());
        int trackTop = y + 1;
        int thumb = Math.max(trackTop, Math.min(trackTop + travel, mouseY - grabOffset));
        int max = maxScroll();
        setScroll((thumb - trackTop) * max / travel);
    }

    private void apply() {
        for (Control control : controls) {
            control.widget.setX(control.layoutX);
            control.widget.setY(control.layoutY - scroll);
        }
        for (Drawer drawer : drawers) {
            drawer.widget.moveTo(drawer.layoutX, drawer.layoutY - scroll);
        }
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - height);
    }

    private boolean onBar(double mouseX) {
        return mouseX >= x + width - 6 && mouseX < x + width;
    }

    private int trackHeight() {
        return Math.max(1, height - 2);
    }

    private int thumbHeight() {
        if (contentHeight <= height) {
            return trackHeight();
        }
        return Math.max(10, trackHeight() * height / contentHeight);
    }

    private int thumbTop() {
        int max = maxScroll();
        if (max <= 0) {
            return y + 1;
        }
        int travel = Math.max(0, trackHeight() - thumbHeight());
        return y + 1 + scroll * travel / max;
    }

    private void drawBar(DrawContext context) {
        if (contentHeight <= height) {
            return;
        }
        int trackX = x + width - 5;
        context.fill(trackX, y + 1, trackX + 3, y + height - 1, 0xFF2A2A2E);
        int top = thumbTop();
        context.fill(trackX, top, trackX + 3, top + thumbHeight(), FRAME);
    }

    private void frame(DrawContext context) {
        context.fill(x, y, x + width, y + 1, FRAME);
        context.fill(x, y + height - 1, x + width, y + height, FRAME);
        context.fill(x, y, x + 1, y + height, FRAME);
        context.fill(x + width - 1, y, x + width, y + height, FRAME);
    }
}
