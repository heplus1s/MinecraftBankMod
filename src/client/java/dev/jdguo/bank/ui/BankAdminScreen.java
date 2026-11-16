package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Operator screen opened by /bank admin. Figures are written in two columns.
 * There is no scrolling ledger panel and no player-tab row.
 */
public final class BankAdminScreen extends Screen {
    private static final String[] CURRENCY_IDS = {"copper", "diamond", "note"};
    private static final String[] CURRENCY_NAMES = {"铜锭", "钻石", "银行券"};

    private enum Extra {
        UNBACKED, FROM_DEPOSITS, TO_DEPOSITS, FULL, FREEZE
    }

    private JsonObject data;
    private String payId = "copper";
    private String amountText = "1";
    private String notice = "";
    private TextFieldWidget amount;
    private ButtonWidget[] currencyButtons = new ButtonWidget[CURRENCY_IDS.length];
    private ButtonWidget unbackedButton;
    private ButtonWidget fromDepositsButton;
    private ButtonWidget toDepositsButton;
    private ButtonWidget fullButton;
    private ButtonWidget freezeButton;
    private BankLayout.AdminBands bands;
    private boolean closing;

    public BankAdminScreen(JsonObject data) {
        super(Text.literal(BankChrome.MANAGEMENT));
        this.data = data == null ? new JsonObject() : data;
    }

    void show(JsonObject next) {
        remember();
        this.data = next == null ? new JsonObject() : next;
        String reported = BankSnapshot.text(this.data, "refusal");
        if (reported != null) {
            notice = reported;
        }
        clearAndInit();
    }

    @Override
    protected void init() {
        int rows = figureRows();
        bands = BankLayout.adminBands(this.width, this.height, rows);
        if (!known(payId)) {
            payId = "copper";
        }
        for (int i = 0; i < CURRENCY_IDS.length; i++) {
            String id = CURRENCY_IDS[i];
            String label = id.equals(payId) ? "▶ " + CURRENCY_NAMES[i] : CURRENCY_NAMES[i];
            int width = Math.max(bands.listW(), textRenderer.getWidth(label) + 8);
            currencyButtons[i] = ButtonWidget.builder(Text.literal(label), button -> choose(id))
                    .dimensions(bands.listX(), bands.listY() + i * 14, width, 14)
                    .build();
            addDrawableChild(currencyButtons[i]);
        }
        amount = new TextFieldWidget(textRenderer, bands.stepperX() + 26, bands.stepperY(), 88, 16, Text.literal("数量"));
        amount.setMaxLength(32);
        amount.setEditable(true);
        amount.setFocusUnlocked(true);
        amount.setDrawsBackground(true);
        amount.setTextPredicate(BankAdminScreen::amountChars);
        amount.setText(amountText == null ? "1" : amountText);
        addDrawableChild(ButtonWidget.builder(Text.literal("－"), button -> adjust(false))
                .dimensions(bands.stepperX(), bands.stepperY(), 22, 16).build());
        addDrawableChild(amount);
        addDrawableChild(ButtonWidget.builder(Text.literal("＋"), button -> adjust(true))
                .dimensions(bands.stepperX() + 26 + 88 + 4, bands.stepperY(), 22, 16).build());
        setFocused(amount);
        amount.setFocused(true);

        String name = currencyName();
        String qty = boxText();
        unbackedButton = extraButton(Extra.UNBACKED, amountButton("无准备金增发", qty, name));
        fromDepositsButton = extraButton(Extra.FROM_DEPOSITS, amountButton("从存款转入准备金", qty, name));
        toDepositsButton = extraButton(Extra.TO_DEPOSITS, amountButton("从准备金转回存款", qty, name));
        fullButton = extraButton(Extra.FULL, "按准备金满额发行 " + name);
        freezeButton = extraButton(Extra.FREEZE, BankBook.freezeButton(data));
        placeExtras();

        int closeWidth = textRenderer.getWidth(BankChrome.CLOSE) + 12;
        addDrawableChild(ButtonWidget.builder(Text.literal(BankChrome.CLOSE), button -> close())
                .dimensions(this.width - BankLayout.EDGE - closeWidth, 4, closeWidth, 16).build());
    }

    private ButtonWidget extraButton(Extra kind, String label) {
        ButtonWidget button = ButtonWidget.builder(Text.literal(label), pressed -> extra(kind))
                .dimensions(0, 0, textRenderer.getWidth(label) + 10, 16).build();
        addDrawableChild(button);
        return button;
    }

    private void placeExtras() {
        ButtonWidget[] buttons = {unbackedButton, fromDepositsButton, toDepositsButton, fullButton, freezeButton};
        int x = BankLayout.EDGE;
        int y = bands.extraY();
        int limit = this.width - BankLayout.EDGE;
        for (ButtonWidget button : buttons) {
            if (button == null) {
                continue;
            }
            if (x > BankLayout.EDGE && x + button.getWidth() > limit) {
                x = BankLayout.EDGE;
                y += 16;
            }
            button.setPosition(x, y);
            x += button.getWidth() + BankLayout.GAP;
        }
    }

    private void choose(String id) {
        remember();
        payId = id;
        clearAndInit();
    }

    private void adjust(boolean issue) {
        String quantity = boxText();
        amountText = quantity;
        if (!typed(quantity)) {
            notice = "请在中间的格子里输入数量。点一次加或减，就按这个数量办理。";
            return;
        }
        notice = "";
        BankUiClient.send(issue
                ? BankActions.adminBackedAmount(payId, quantity)
                : BankActions.adminRetire(payId, quantity));
    }

    private void extra(Extra kind) {
        remember();
        if (kind == Extra.FREEZE) {
            notice = "";
            BankUiClient.send(BankActions.adminFreeze());
            return;
        }
        if (kind == Extra.FULL) {
            notice = "";
            BankUiClient.send(BankActions.adminBacked(payId));
            return;
        }
        String quantity = boxText();
        amountText = quantity;
        if (!typed(quantity)) {
            notice = "请在中间的格子里输入数量。";
            return;
        }
        notice = "";
        String command = switch (kind) {
            case UNBACKED -> BankActions.adminUnbacked(payId, quantity);
            case FROM_DEPOSITS -> BankActions.adminReserveFromDeposits(payId, quantity);
            case TO_DEPOSITS -> BankActions.adminReserveToDeposits(payId, quantity);
            default -> null;
        };
        if (command != null) {
            BankUiClient.send(command);
        }
    }

    private static boolean typed(String quantity) {
        if (quantity == null || quantity.isBlank() || "all".equalsIgnoreCase(quantity) || "全部".equals(quantity)) {
            return false;
        }
        return BankRates.parse(quantity) != null && BankRates.parse(quantity).signum() > 0;
    }

    @Override
    public void tick() {
        if (amount != null) {
            amount.tick();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (amount != null && amount.isMouseOver(mouseX, mouseY)) {
            setFocused(amount);
            amount.setFocused(true);
        }
        return handled || (amount != null && amount.isMouseOver(mouseX, mouseY));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        syncLabels();
        renderBackground(context);
        drawFigures(context);
        for (int i = 0; i < currencyButtons.length; i++) {
            ButtonWidget button = currencyButtons[i];
            if (button != null && CURRENCY_IDS[i].equals(payId)) {
                context.fill(button.getX() - 1, button.getY() - 1, button.getX() + button.getWidth() + 1,
                        button.getY() + button.getHeight() + 1, 0xFFC8A060);
            }
        }
        super.render(context, mouseX, mouseY, delta);
        if (!notice.isBlank()) {
            context.drawText(textRenderer, notice, this.width / 2 + 4, bands.quoteY(), 0xFFFFCC88, false);
        }
    }

    private void drawFigures(DrawContext context) {
        int step = bands.lineStep();
        String diamond = BankRates.centralQuote(data);
        String note = BankRates.bankNoteQuote(data);
        context.drawText(textRenderer, diamond, BankLayout.EDGE, bands.quoteY(), quoteColor(diamond), false);
        context.drawText(textRenderer, note, BankLayout.EDGE, bands.quoteY() + step, quoteColor(note), false);

        int left = BankLayout.EDGE;
        int right = this.width / 2 + 4;
        int y = bands.figureY();
        y = drawPairs(context, BankBook.circulationLines(data), left, right, y, step);
        y = drawColumns(context,
                BankBook.metalLines(data, "copper", "铜锭", "copperReserve"),
                BankBook.metalLines(data, "diamond", "钻石", "diamondReserve"),
                left, right, y, step);
        drawPairs(context, BankBook.noteLines(data), left, right, y, step);
    }

    private int drawColumns(DrawContext context, List<String> leftLines, List<String> rightLines,
                            int left, int right, int y, int step) {
        int rows = Math.max(leftLines.size(), rightLines.size());
        for (int i = 0; i < rows; i++) {
            if (y + 9 > bands.listY()) {
                break;
            }
            if (i < leftLines.size()) {
                context.drawText(textRenderer, leftLines.get(i), left, y, lineColor(leftLines.get(i)), false);
            }
            if (i < rightLines.size()) {
                context.drawText(textRenderer, rightLines.get(i), right, y, lineColor(rightLines.get(i)), false);
            }
            y += step;
        }
        return y;
    }

    private int drawPairs(DrawContext context, List<String> lines, int left, int right, int y, int step) {
        int half = (lines.size() + 1) / 2;
        for (int i = 0; i < half; i++) {
            if (y + 9 > bands.listY()) {
                break;
            }
            context.drawText(textRenderer, lines.get(i), left, y, lineColor(lines.get(i)), false);
            if (half + i < lines.size()) {
                context.drawText(textRenderer, lines.get(half + i), right, y, lineColor(lines.get(half + i)), false);
            }
            y += step;
        }
        return y;
    }

    /** Button text for an amount action. A blank box is not rewritten as 1. */
    static String amountButton(String action, String typed, String currency) {
        String amount = typed == null ? "" : typed.trim();
        if (amount.isEmpty()) {
            return action + " " + currency;
        }
        return action + " " + amount + " " + currency;
    }

    /** Digits and at most four decimal places, including an unfinished fraction. */
    static boolean amountChars(String text) {
        return text != null && (text.isEmpty() || text.matches("\\d*(\\.\\d{0,4})?"));
    }

    private void syncLabels() {
        String name = currencyName();
        String qty = boxText();
        boolean moved = false;
        moved |= fit(unbackedButton, amountButton("无准备金增发", qty, name));
        moved |= fit(fromDepositsButton, amountButton("从存款转入准备金", qty, name));
        moved |= fit(toDepositsButton, amountButton("从准备金转回存款", qty, name));
        moved |= fit(fullButton, "按准备金满额发行 " + name);
        moved |= fit(freezeButton, BankBook.freezeButton(data));
        if (moved) {
            placeExtras();
        }
    }

    private boolean fit(ButtonWidget button, String text) {
        if (button == null) {
            return false;
        }
        if (!text.equals(button.getMessage().getString())) {
            button.setMessage(Text.literal(text));
        }
        int width = Math.max(20, textRenderer.getWidth(text) + 10);
        if (button.getWidth() == width) {
            return false;
        }
        button.setWidth(width);
        return true;
    }

    private int figureRows() {
        int circulation = (BankBook.circulationLines(data).size() + 1) / 2;
        int metals = Math.max(
                BankBook.metalLines(data, "copper", "铜锭", "copperReserve").size(),
                BankBook.metalLines(data, "diamond", "钻石", "diamondReserve").size());
        int notes = (BankBook.noteLines(data).size() + 1) / 2;
        return circulation + metals + notes;
    }

    private void remember() {
        if (amount != null && amount.getText() != null) {
            amountText = amount.getText();
        }
    }

    private String boxText() {
        if (amount != null && amount.getText() != null) {
            return amount.getText().trim();
        }
        return amountText == null ? "" : amountText.trim();
    }

    private String currencyName() {
        for (int i = 0; i < CURRENCY_IDS.length; i++) {
            if (CURRENCY_IDS[i].equals(payId)) {
                return CURRENCY_NAMES[i];
            }
        }
        return "铜锭";
    }

    private static boolean known(String id) {
        for (String currency : CURRENCY_IDS) {
            if (currency.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static int quoteColor(String line) {
        return line.contains(BankRates.UNAVAILABLE) ? 0xFFAAAAAA : 0xFFE8D090;
    }

    private static int lineColor(String line) {
        if (line.contains(BankBook.MISSING) || BankBook.CIRCULATION_NOTE.equals(line)) {
            return 0xFFAAAAAA;
        }
        return 0xFFE6E6E6;
    }

    @Override
    public void close() {
        if (!closing) {
            closing = true;
            BankUiClient.send(BankActions.close());
        }
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
