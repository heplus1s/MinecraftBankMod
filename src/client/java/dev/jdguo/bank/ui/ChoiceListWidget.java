package dev.jdguo.bank.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Option drawer. Collapsed, it shows only the current choice.
 * Open, the rows scroll inside a frame and cannot paint outside it.
 */
final class ChoiceListWidget extends AlwaysSelectedEntryListWidget<ChoiceListWidget.Row> {
    static final int HEADER = 20;
    private static final int FRAME = 0xFFC8A060;
    private static final int FILL = 0xFF101014;

    private boolean drawer = true;
    private boolean expanded;
    private int scroll;
    private boolean toggle;
    private boolean picked;

    ChoiceListWidget(MinecraftClient client, int x, int y, int width, int height) {
        super(client, width, height, y, y + height, 18);
        setLeftPos(x);
        setRenderBackground(false);
        setRenderHorizontalShadows(false);
        setRenderSelection(false);
    }

    void setDrawer(boolean drawer) {
        this.drawer = drawer;
        if (!drawer) {
            expanded = true;
        }
    }

    boolean isDrawer() {
        return drawer;
    }

    boolean expanded() {
        return expanded;
    }

    void setExpanded(boolean expanded) {
        this.expanded = !drawer || expanded;
    }

    boolean pollToggle() {
        boolean value = toggle;
        toggle = false;
        return value;
    }

    boolean pollPick() {
        boolean value = picked;
        picked = false;
        return value;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!drawer) {
            renderPanel(context, mouseX, mouseY);
            return;
        }
        int bottom = expanded ? this.bottom : this.top + HEADER;
        frame(context, this.left, this.top, this.right, bottom);
        String label = selectedLabel();
        if (label.isBlank()) {
            label = "尚未选择";
        }
        context.drawText(client.textRenderer, label, this.left + 6, this.top + 6, 0xFFFFFFFF, true);
        String cue = expanded ? "收起" : "展开";
        int cueWidth = client.textRenderer.getWidth(cue);
        context.drawText(client.textRenderer, cue, this.right - cueWidth - 6, this.top + 6, 0xFFE8D090, false);
        if (!expanded) {
            return;
        }
        int innerLeft = this.left + 1;
        int innerTop = this.top + HEADER;
        int innerRight = this.right - 1;
        int innerBottom = this.bottom - 1;
        if (innerBottom <= innerTop || innerRight <= innerLeft) {
            return;
        }
        context.enableScissor(innerLeft, innerTop, innerRight, innerBottom);
        int rowWidth = Math.max(20, getRowWidth());
        int rowX = getRowLeft();
        int index = 0;
        for (Row row : children()) {
            int rowY = innerTop + index * this.itemHeight - scroll;
            if (rowY + this.itemHeight > innerTop && rowY < innerBottom) {
                boolean hovered = mouseX >= innerLeft && mouseX < innerRight && mouseY >= rowY && mouseY < rowY + this.itemHeight;
                row.render(context, index, rowY, rowX, rowWidth, this.itemHeight, mouseX, mouseY, hovered, delta);
            }
            index++;
        }
        context.disableScissor();
        frame(context, this.left, this.top, this.right, this.bottom);
    }

    private void renderPanel(DrawContext context, int mouseX, int mouseY) {
        frame(context, this.left, this.top, this.right, this.bottom);
        int innerLeft = this.left + 1;
        int innerTop = this.top + 2;
        int innerRight = this.right - 1;
        int innerBottom = this.bottom - 1;
        if (innerBottom <= innerTop) {
            return;
        }
        context.enableScissor(innerLeft, innerTop, innerRight, innerBottom);
        int rowWidth = Math.max(20, getRowWidth());
        int rowX = getRowLeft();
        int index = 0;
        for (Row row : children()) {
            int rowY = innerTop + index * this.itemHeight - scroll;
            if (rowY + this.itemHeight > innerTop && rowY < innerBottom) {
                boolean hovered = mouseX >= innerLeft && mouseX < innerRight && mouseY >= rowY && mouseY < rowY + this.itemHeight;
                row.render(context, index, rowY, rowX, rowWidth, this.itemHeight, mouseX, mouseY, hovered, 0);
            }
            index++;
        }
        context.disableScissor();
        frame(context, this.left, this.top, this.right, this.bottom);
    }

    private static void frame(DrawContext context, int left, int top, int right, int bottom) {
        context.fill(left, top, right, bottom, FILL);
        context.fill(left, top, right, top + 1, FRAME);
        context.fill(left, bottom - 1, right, bottom, FRAME);
        context.fill(left, top, left + 1, bottom, FRAME);
        context.fill(right - 1, top, right, bottom, FRAME);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX < this.left || mouseX >= this.right || mouseY < this.top || mouseY >= this.bottom) {
            return false;
        }
        if (drawer && mouseY < this.top + HEADER) {
            toggle = true;
            return true;
        }
        if (drawer && !expanded) {
            return true;
        }
        int innerTop = drawer ? this.top + HEADER : this.top + 2;
        int index = 0;
        for (Row row : children()) {
            int rowY = innerTop + index * this.itemHeight - scroll;
            if (mouseY >= rowY && mouseY < rowY + this.itemHeight && rowY < this.bottom && rowY + this.itemHeight > innerTop) {
                if (row.choice.section()) {
                    return true;
                }
                setSelected(row);
                picked = true;
                return true;
            }
            index++;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if ((drawer && !expanded) || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        int origin = drawer ? this.top + HEADER : this.top + 2;
        int view = Math.max(0, (this.bottom - 1) - origin);
        int max = Math.max(0, children().size() * this.itemHeight - view);
        int next = Math.max(0, Math.min(max, scroll - (int) Math.round(amount * this.itemHeight)));
        if (next == scroll) {
            return false;
        }
        scroll = next;
        return true;
    }

    void moveTo(int x, int y) {
        setLeftPos(x);
        this.top = y;
        this.bottom = y + this.height;
    }

    void setChoices(List<Choice> choices) {
        String keep = selectedId();
        clearEntries();
        for (Choice choice : choices) {
            addEntry(new Row(choice));
        }
        if (keep != null) {
            for (Row row : children()) {
                if (keep.equals(row.choice.id)) {
                    setSelected(row);
                    break;
                }
            }
        }
    }

    String selectedId() {
        Row row = getSelectedOrNull();
        return row == null ? null : row.choice.id;
    }

    String selectedLabel() {
        Row row = getSelectedOrNull();
        if (row == null || row.choice.section()) {
            return "";
        }
        return row.choice.label;
    }

    void select(String id) {
        if (id == null || id.isBlank() || id.startsWith("group:")) {
            return;
        }
        for (Row row : children()) {
            if (id.equals(row.choice.id)) {
                setSelected(row);
                return;
            }
        }
    }

    @Override
    public int getRowWidth() {
        return width - 12;
    }

    @Override
    protected int getScrollbarPositionX() {
        return getRowLeft() + getRowWidth();
    }

    record Choice(String id, String label, boolean section) {
        Choice(String id, String label) {
            this(id, label, false);
        }
    }

    final class Row extends AlwaysSelectedEntryListWidget.Entry<Row> {
        private final Choice choice;

        Row(Choice choice) {
            this.choice = choice;
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
                           int mouseX, int mouseY, boolean hovered, float tickDelta) {
            boolean selected = !choice.section() && getSelectedOrNull() == this;
            if (!choice.section() && (selected || hovered)) {
                context.fill(x, y, x + entryWidth, y + entryHeight - 1, selected ? 0xAA3A5A38 : 0x66333333);
            }
            int color = choice.section() ? 0xFFE8D090 : 0xFFFFFFFF;
            int textX = x + (choice.section() ? 4 : 12);
            context.drawText(MinecraftClient.getInstance().textRenderer, choice.label, textX, y + 4, color, true);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!choice.section()) {
                setSelected(this);
            }
            return true;
        }

        @Override
        public Text getNarration() {
            return Text.literal(choice.label);
        }
    }
}
