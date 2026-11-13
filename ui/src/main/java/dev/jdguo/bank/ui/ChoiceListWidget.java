package dev.jdguo.bank.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.text.Text;

import java.util.List;

/** Scrollable list. The visible line is a display name; the id is never drawn. */
final class ChoiceListWidget extends AlwaysSelectedEntryListWidget<ChoiceListWidget.Row> {
    ChoiceListWidget(MinecraftClient client, int x, int y, int width, int height) {
        super(client, width, height, y, y + height, 18);
        setLeftPos(x);
        setRenderBackground(false);
        setRenderHorizontalShadows(false);
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
        return row == null ? "" : row.choice.label;
    }

    void select(String id) {
        if (id == null) {
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

    record Choice(String id, String label) {}

    final class Row extends AlwaysSelectedEntryListWidget.Entry<Row> {
        private final Choice choice;

        Row(Choice choice) {
            this.choice = choice;
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
                           int mouseX, int mouseY, boolean hovered, float tickDelta) {
            boolean selected = getSelectedOrNull() == this;
            if (selected || hovered) {
                context.fill(x, y, x + entryWidth, y + entryHeight, selected ? 0x663A5A38 : 0x33222222);
            }
            context.drawText(MinecraftClient.getInstance().textRenderer, choice.label, x + 4, y + 4, 0xFFE8E8E8, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            setSelected(this);
            return true;
        }

        @Override
        public Text getNarration() {
            return Text.literal(choice.label);
        }
    }
}
