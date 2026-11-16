package dev.jdguo.bank.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Left rate column, centered main area, two function rows, and a separate close row.
 * Top-right buttons are stacked with a gap so their hit boxes do not touch.
 */
public final class BankLayout {
    public static final int EDGE = 8;
    public static final int GAP = 4;
    public static final int BUTTON_HEIGHT = 20;
    public static final int TOP_GAP = 8;

    public record Box(String label, int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean fits(int screenWidth, int screenHeight) {
            return x >= 0 && y >= 0 && right() <= screenWidth && bottom() <= screenHeight;
        }

        public boolean intersects(Box other) {
            return x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
        }
    }

    private BankLayout() {
    }

    /** Collapsed drawers are one header. Open drawers add a clipped row area. */
    public static int drawerHeight(boolean open, int rows) {
        if (!open) {
            return 20;
        }
        int count = Math.max(1, rows);
        return 20 + Math.min(72, Math.max(36, count * 18));
    }

    /**
     * Personal-note trade. {@code top} is already below the title and the help line.
     * Drawers are never moved up into that header. An open list keeps a row area
     * unless the tab row would otherwise be covered.
     */
    public record MarketStack(int firstY, int firstH, int secondY, int secondH, int fieldY, int quickY, int actionY) {
        public boolean separated(int bottom) {
            return secondY >= firstY + firstH + 4
                    && fieldY >= secondY + secondH + 24
                    && quickY >= fieldY + 16
                    && actionY >= quickY + 18
                    && actionY + 18 <= bottom;
        }
    }

    public static MarketStack marketStack(int top, int bottom, boolean firstOpen, int firstRows, boolean secondOpen, int secondRows) {
        int gap = 6;
        int balance = 30;
        int tail = 16 + 4 + 18 + 4 + 18;
        int firstH = drawerHeight(firstOpen, firstRows);
        int secondH = drawerHeight(secondOpen, secondRows);
        int overflow = top + firstH + gap + secondH + gap + balance + tail - bottom;
        if (overflow > 0 && firstOpen) {
            firstH = Math.max(56, firstH - overflow);
            overflow = top + firstH + gap + secondH + gap + balance + tail - bottom;
        }
        if (overflow > 0 && secondOpen) {
            secondH = Math.max(56, secondH - overflow);
            overflow = top + firstH + gap + secondH + gap + balance + tail - bottom;
        }
        if (overflow > 0 && firstOpen && firstH > 38) {
            firstH = Math.max(38, firstH - overflow);
            overflow = top + firstH + gap + secondH + gap + balance + tail - bottom;
        }
        if (overflow > 0 && secondOpen && secondH > 38) {
            secondH = Math.max(38, secondH - overflow);
        }
        int secondY = top + firstH + gap;
        int fieldY = secondY + secondH + gap + balance;
        int quickY = fieldY + 20;
        int actionY = quickY + 22;
        return new MarketStack(top, firstH, secondY, secondH, fieldY, quickY, actionY);
    }

    /**
     * Personal issuance. Regime drawer, then anchor drawer, then the amount and action rows.
     * An open list stays above the amount box.
     */
    public record IssuanceStack(
            int regimeY, int regimeH, int anchorY, int anchorH, int fieldY, int quickY, int depositY, int issueY) {
        public boolean separated(int bottom) {
            return anchorY >= regimeY + regimeH + 4
                    && fieldY >= anchorY + anchorH + 4
                    && quickY >= fieldY + 16
                    && depositY >= quickY + 18
                    && issueY >= depositY + 18
                    && issueY + 18 <= bottom;
        }
    }

    public static IssuanceStack issuanceStack(int top, int bottom, boolean regimeOpen, boolean anchorOpen, int anchorRows) {
        int gap = 6;
        int regimeH = drawerHeight(regimeOpen, 3);
        int anchorH = drawerHeight(anchorOpen, Math.max(1, anchorRows));
        int tail = gap + 16 + 4 + 18 + 4 + 18 + 4 + 18;
        int overflow = top + regimeH + gap + anchorH + tail - bottom;
        if (overflow > 0 && regimeOpen) {
            regimeH = Math.max(38, regimeH - overflow);
            overflow = top + regimeH + gap + anchorH + tail - bottom;
        }
        if (overflow > 0 && anchorOpen) {
            anchorH = Math.max(38, anchorH - overflow);
        }
        int anchorY = top + regimeH + gap;
        int fieldY = anchorY + anchorH + gap;
        int quickY = fieldY + 20;
        int depositY = quickY + 22;
        int issueY = depositY + 22;
        return new IssuanceStack(top, regimeH, anchorY, anchorH, fieldY, quickY, depositY, issueY);
    }

    public static int labelWidth(String label) {
        return label.codePointCount(0, label.length()) * 12 + 18;
    }

    public static int columnWidth(int screenWidth) {
        int room = screenWidth - EDGE * 2 - GAP * 2 - 150;
        return Math.max(96, Math.min(132, room));
    }

    public static int columnRight(int screenWidth) {
        return EDGE + columnWidth(screenWidth);
    }

    public static int mainLeft(int screenWidth) {
        return columnRight(screenWidth) + GAP * 2;
    }

    public static int mainRight(int screenWidth) {
        return screenWidth - EDGE;
    }

    public static int mainWidth(int screenWidth) {
        return Math.max(0, mainRight(screenWidth) - mainLeft(screenWidth));
    }

    public static int closeY(int screenHeight) {
        return screenHeight - EDGE - BUTTON_HEIGHT;
    }

    /** Lower function row shares 关闭's top, so their bottom edges line up. */
    public static int row2Y(int screenHeight) {
        return closeY(screenHeight);
    }

    public static int row1Y(int screenHeight) {
        return row2Y(screenHeight) - GAP - BUTTON_HEIGHT;
    }

    public static int contentBottom(int screenHeight) {
        return row1Y(screenHeight) - GAP;
    }

    /**
     * Visible window for the personal-issuance body.
     * {@code titleHeight} is the pinned title block, already including its gap.
     * The port ends at the nav, so the body scrolls inside it instead of covering 关闭.
     */
    public static Box issuancePort(int screenWidth, int screenHeight, int titleHeight) {
        int top = contentTop() + Math.max(12, titleHeight);
        int bottom = contentBottom(screenHeight);
        return new Box("个人发行", mainLeft(screenWidth), top, mainWidth(screenWidth), Math.max(0, bottom - top));
    }

    /** Buttons in one row until the next one would pass {@code right}, then the next row. */
    public record Flow(List<Box> boxes, int bottom) {
    }

    public static Flow flow(int x, int y, int right, int gap, int boxHeight, List<String> labels, List<Integer> widths) {
        List<Box> boxes = new ArrayList<>();
        int limit = Math.max(x + 20, right);
        int cursorX = x;
        int cursorY = y;
        for (int i = 0; i < labels.size(); i++) {
            int room = Math.max(20, limit - x);
            int width = Math.max(20, Math.min(widths.get(i), room));
            if (cursorX > x && cursorX + width > limit) {
                cursorY += boxHeight + gap;
                cursorX = x;
            }
            boxes.add(new Box(labels.get(i), cursorX, cursorY, width, boxHeight));
            cursorX += width + gap;
        }
        return new Flow(boxes, boxes.isEmpty() ? y : cursorY + boxHeight);
    }

    public static Box issuanceBox(int screenWidth) {
        return topRight(screenWidth, EDGE, BankChrome.ISSUANCE, labelWidth(BankChrome.ISSUANCE));
    }

    public static Box managementBox(int screenWidth) {
        return topRight(screenWidth, EDGE + BUTTON_HEIGHT + TOP_GAP, BankChrome.MANAGEMENT, labelWidth(BankChrome.MANAGEMENT));
    }

    public static Box topRight(int screenWidth, int y, String label, int width) {
        return new Box(label, screenWidth - EDGE - width, y, width, BUTTON_HEIGHT);
    }

    /**
     * Player pages start under 个人发行. 中央银行管理 is a separate screen,
     * so this gap does not reserve a second top button.
     */
    public static int contentTop() {
        return EDGE + BUTTON_HEIGHT + GAP;
    }

    /**
     * Drawers stay in the upper part of the main column. The amount, the 1/5/10/64 row,
     * and the confirm button stay on their own rows underneath, whether a drawer is open or not.
     */
    public record PlayerControls(int drawerY, int fieldY, int quickY, int actionY) {
        public Band band() {
            return new Band(drawerY, 20, fieldY, quickY, actionY);
        }

        public boolean amountBelowDrawers(int drawerRows) {
            int rows = Math.max(1, drawerRows);
            int lastDrawer = drawerY + (rows - 1) * 24;
            return lastDrawer + 38 <= fieldY && fieldY + 16 <= quickY && quickY + 18 <= actionY;
        }
    }

    public static PlayerControls playerControls(int screenHeight, int drawerRows) {
        int rows = Math.max(1, drawerRows);
        int bottom = contentBottom(screenHeight);
        int drawerY = contentTop() + 26;
        int openSlot = 20 + 72;
        int fieldY = drawerY + (rows - 1) * 24 + openSlot + 12;
        int quickY = fieldY + 16;
        int actionY = quickY + 18;
        if (actionY + 18 > bottom) {
            int overflow = actionY + 18 - bottom;
            openSlot = Math.max(38, openSlot - overflow);
            fieldY = drawerY + (rows - 1) * 24 + openSlot + 12;
            quickY = fieldY + 16;
            actionY = quickY + 18;
        }
        if (actionY + 18 > bottom) {
            int lift = Math.min(actionY + 18 - bottom, Math.max(0, drawerY - contentTop()));
            drawerY -= lift;
            fieldY -= lift;
            quickY -= lift;
            actionY -= lift;
        }
        return new PlayerControls(drawerY, fieldY, quickY, actionY);
    }

    /**
     * Operator page. Figures are two columns of text, not a scrolling panel.
     * The currency list is three short rows, centered, and stops above the amount.
     */
    public record AdminBands(
            int quoteY,
            int figureY,
            int figureBottom,
            int listX,
            int listY,
            int listW,
            int listH,
            int stepperX,
            int stepperY,
            int extraY,
            int lineStep) {
        public boolean separated() {
            return figureY >= quoteY + 2 * lineStep
                    && figureBottom <= listY
                    && listH == 42
                    && listW <= 120
                    && listY + listH + 2 <= stepperY
                    && stepperY + 16 <= extraY;
        }

        public boolean centered(int screenWidth) {
            return listX > EDGE
                    && listX + listW < screenWidth - EDGE
                    && Math.abs(listX + listW / 2 - screenWidth / 2) <= 1;
        }

        public boolean fits(int screenHeight) {
            return extraY + 16 * 3 <= screenHeight - 2 && quoteY >= 2;
        }
    }

    /** Worst case is ten figure rows: two aggregate rows, five metal rows, three note rows. */
    public static AdminBands adminBands(int screenWidth, int screenHeight) {
        return adminBands(screenWidth, screenHeight, 10);
    }

    public static AdminBands adminBands(int screenWidth, int screenHeight, int figureRows) {
        int step = screenHeight >= 320 ? 12 : 10;
        int gap = screenHeight >= 320 ? 6 : 2;
        int quoteY = 4;
        int figureY = quoteY + 2 * step + gap;
        int rows = Math.max(1, figureRows);
        int figureBottom = figureY + rows * step;
        int listH = 42;
        int listW = 88;
        int listY = figureBottom + gap;
        int listX = Math.max(EDGE, (screenWidth - listW) / 2);
        int stepperY = listY + listH + gap;
        int stepperW = 22 + 4 + 88 + 4 + 22;
        int stepperX = Math.max(EDGE, (screenWidth - stepperW) / 2);
        int extraY = stepperY + 16 + gap;
        return new AdminBands(quoteY, figureY, figureBottom, listX, listY, listW, listH, stepperX, stepperY, extraY, step);
    }

    public static Box closeBox(int screenWidth, int screenHeight) {
        int width = labelWidth(BankChrome.CLOSE);
        return new Box(BankChrome.CLOSE, screenWidth - EDGE - width, closeY(screenHeight), width, BUTTON_HEIGHT);
    }

    public static List<Box> functionTabs(int screenWidth, int screenHeight) {
        List<Box> boxes = new ArrayList<>();
        boxes.addAll(row(EDGE, row1Y(screenHeight), BankChrome.ROW_ONE));
        boxes.addAll(row(EDGE, row2Y(screenHeight), BankChrome.ROW_TWO));
        return boxes;
    }

    /**
     * Puts the scrollable list in the gap above the amount field.
     * The list always keeps a visible height and ends before the field.
     */
    public record Band(int listTop, int listHeight, int fieldY, int quickY, int actionY) {
        public int listBottom() {
            return listTop + listHeight;
        }

        public boolean separated() {
            return listHeight >= 18 && listBottom() + 8 <= fieldY && fieldY + 16 <= quickY && quickY + 18 <= actionY;
        }
    }

    /**
     * Currency list on the left of the main area, ledger lines beside it, amount controls below both.
     * {@code backedY} is the full-issue button. Its explanation occupies {@code backedTop} through that button.
     */
    public record ManagementPane(
            int listTop, int listHeight, int bookY, int fieldY, int quickY, int actionY, int backedTop, int backedY) {
        public boolean bookBesideList() {
            return bookY == listTop;
        }

        public boolean separated() {
            return listHeight >= 18
                    && listTop + listHeight + 6 <= fieldY
                    && fieldY + 16 <= quickY
                    && quickY + 18 <= actionY
                    && actionY + 18 <= backedTop
                    && backedTop <= backedY;
        }
    }

    /** Button and the explanation that sits beside it, or on the lines just above it. */
    public record BackedControl(int buttonX, int buttonY, int buttonWidth, int textX, int textY, int textWidth, int textLines) {
        public boolean inside(int mainLeft, int mainRight) {
            return buttonX >= mainLeft
                    && buttonX + buttonWidth <= mainRight
                    && textX >= mainLeft
                    && textWidth > 0
                    && textX < mainRight;
        }
    }

    public static Band band(int contentTop, int contentBottom, int titleGap) {
        int listTop = contentTop + Math.max(14, titleGap);
        int actionY = contentBottom - 18;
        int quickY = actionY - 22;
        int fieldY = quickY - 20;
        int listHeight = fieldY - 12 - listTop;
        if (listHeight < 18) {
            listHeight = 18;
            fieldY = listTop + listHeight + 12;
            quickY = fieldY + 20;
            actionY = quickY + 22;
            if (actionY + 18 > contentBottom) {
                int shift = actionY + 18 - contentBottom;
                int lift = Math.min(shift, Math.max(0, listTop - contentTop - 14));
                listTop -= lift;
                fieldY -= lift;
                quickY -= lift;
                actionY -= lift;
                if (actionY + 18 > contentBottom) {
                    int extra = actionY + 18 - contentBottom;
                    actionY -= extra;
                    quickY -= extra;
                    fieldY -= extra;
                    listHeight = Math.max(18, fieldY - 8 - listTop);
                }
            }
        }
        return new Band(listTop, listHeight, fieldY, quickY, actionY);
    }

    public static ManagementPane management(int contentTop, int contentBottom, int prefaceLines, int backedBlock) {
        int block = Math.max(18, backedBlock);
        int backedY = contentBottom - 18;
        int backedTop = block <= 20 ? backedY : backedY - (block - 18);
        int actionY = backedTop - 4 - 18;
        int quickY = actionY - 4 - 18;
        int fieldY = quickY - 4 - 16;
        int listTop = contentTop + 16 + Math.max(0, prefaceLines) * 12;
        int listHeight = fieldY - 8 - listTop;
        if (listHeight < 18) {
            int liftedTop = Math.max(contentTop + 12, fieldY - 8 - 36);
            listTop = Math.min(listTop, liftedTop);
            listHeight = fieldY - 8 - listTop;
        }
        if (listHeight > 108) {
            listHeight = 108;
        }
        if (listHeight < 18 || listTop + listHeight + 6 > fieldY || actionY + 18 > backedTop) {
            backedY = contentBottom - 18;
            backedTop = backedY;
            actionY = backedTop - 2 - 18;
            quickY = actionY - 2 - 18;
            fieldY = quickY - 2 - 16;
            listTop = Math.max(contentTop + 12, fieldY - 8 - 36);
            listHeight = fieldY - 6 - listTop;
            if (listHeight > 108) {
                listHeight = 108;
            }
            if (listHeight < 18) {
                listTop = contentTop;
                listHeight = Math.max(18, fieldY - 6 - listTop);
            }
        }
        return new ManagementPane(listTop, listHeight, listTop, fieldY, quickY, actionY, backedTop, backedY);
    }

    public static int backedBlockHeight(int mainWidth, int buttonWidth, int textWidth) {
        if (buttonWidth + 8 + textWidth <= mainWidth) {
            return 20;
        }
        int lines = Math.max(1, (textWidth + Math.max(1, mainWidth) - 1) / Math.max(1, mainWidth));
        return lines * 12 + 4 + 18;
    }

    public static BackedControl backedControl(int screenWidth, int buttonY, int buttonWidth, int textWidth) {
        int left = mainLeft(screenWidth);
        int right = mainRight(screenWidth);
        int room = Math.max(0, right - left);
        int gap = 8;
        if (buttonWidth + gap + textWidth <= room) {
            int start = left + Math.max(0, (room - buttonWidth - gap - textWidth) / 2);
            return new BackedControl(start, buttonY, buttonWidth, start + buttonWidth + gap, buttonY + 5, textWidth, 1);
        }
        int buttonX = left + Math.max(0, (room - buttonWidth) / 2);
        int lines = Math.max(1, (textWidth + Math.max(1, room) - 1) / Math.max(1, room));
        return new BackedControl(buttonX, buttonY, buttonWidth, left, buttonY - 4 - lines * 12, room, lines);
    }

    public static List<Box> row(int x, int y, List<String> labels) {
        List<Box> boxes = new ArrayList<>();
        int cursor = x;
        for (String label : labels) {
            int width = labelWidth(label);
            boxes.add(new Box(label, cursor, y, width, BUTTON_HEIGHT));
            cursor += width + GAP;
        }
        return boxes;
    }
}
