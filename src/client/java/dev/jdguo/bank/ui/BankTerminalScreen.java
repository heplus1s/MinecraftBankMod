package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional bank screen. Rates stay in the left column. Choices are lists.
 * Main controls are centered in the space to the right of that column.
 */
public final class BankTerminalScreen extends Screen {
    private enum Page {
        SAVINGS, DEPOSIT, WITHDRAW, EXCHANGE, ISSUE, REDEEM, MARKET, ISSUANCE, ISSUANCE_SWITCH,
        MANAGEMENT, MANAGEMENT_ADJUST, CONFIRM
    }

    private record Placed(String role, ChoiceListWidget widget, int x, int y, int width, int height, boolean inScroll) {
    }

    private JsonObject data;
    private Page page = Page.SAVINGS;
    private String payId = "";
    private String receiveId = "";
    private String noteName = "";
    private String otherNote = "";
    private String marketMode = "buy";
    private boolean payeeSelf;
    private String amountText = "1";
    private String notice = "";
    private String openDrawer = "";
    private String switchRegime = "";
    private String switchAnchor = "";
    private int marketBalanceY = -1;
    private int bookScroll;
    private int issuanceScroll;
    private IssuancePane issuancePane;
    private int bookX;
    private int bookY;
    private int bookW;
    private int bookH;
    private ButtonWidget plusButton;
    private ButtonWidget unbackedButton;
    private ButtonWidget retireButton;
    private ButtonWidget fromDepositsButton;
    private ButtonWidget toDepositsButton;
    private ButtonWidget backedButton;
    private boolean closing;
    private TextFieldWidget amount;
    private final List<Placed> placed = new ArrayList<>();

    public BankTerminalScreen(JsonObject data) {
        super(Text.literal(BankChrome.SAVINGS_TITLE));
        this.data = data == null ? new JsonObject() : data;
    }

    void show(JsonObject next) {
        captureSelections();
        if (issuancePane != null) {
            issuanceScroll = issuancePane.scroll();
        }
        boolean confirm = BankSnapshot.confirm(next);
        this.data = next == null ? new JsonObject() : next;
        String reported = BankSnapshot.text(this.data, "refusal");
        if (reported != null && !reported.equals(notice)) {
            notice = reported;
            issuanceScroll = 0;
        } else if (reported != null) {
            notice = reported;
        }
        if (confirm) {
            page = Page.CONFIRM;
        } else if (page == Page.CONFIRM) {
            page = Page.ISSUANCE;
        }
        clearAndInit();
    }

    @Override
    protected void init() {
        amount = null;
        issuancePane = null;
        pageBand = null;
        marketBalanceY = -1;
        placed.clear();
        plusButton = null;
        unbackedButton = null;
        retireButton = null;
        fromDepositsButton = null;
        toDepositsButton = null;
        backedButton = null;
        managementPane = null;
        managementBookBeside = false;
        if (page == Page.MANAGEMENT || page == Page.MANAGEMENT_ADJUST) {
            page = Page.SAVINGS;
        }
        if (page == Page.CONFIRM) {
            addTopButtons();
            addClose();
            initConfirm();
            return;
        }
        addChrome();
        switch (page) {
            case DEPOSIT -> initMoney(BankSnapshot.currencies(data), true, "确认存入");
            case WITHDRAW -> initMoney(BankSnapshot.currencies(data), true, "确认取出");
            case EXCHANGE -> initExchange();
            case ISSUE -> initIssue();
            case REDEEM -> initRedeem();
            case MARKET -> initMarket();
            case ISSUANCE -> initIssuance();
            case ISSUANCE_SWITCH -> initIssuanceSwitch();
            default -> {
            }
        }
    }

    private void addChrome() {
        addTopButtons();
        addRow(BankChrome.ROW_ONE, BankLayout.row1Y(this.height));
        addRow(BankChrome.ROW_TWO, BankLayout.row2Y(this.height));
        addClose();
    }

    private void addTopButtons() {
        int issueWidth = buttonWidth(BankChrome.ISSUANCE);
        BankLayout.Box issue = BankLayout.topRight(this.width, BankLayout.EDGE, BankChrome.ISSUANCE, issueWidth);
        addDrawableChild(ButtonWidget.builder(Text.literal(issue.label()), button -> open(Page.ISSUANCE))
                .dimensions(issue.x(), issue.y(), issue.width(), issue.height()).build());
    }

    private void addRow(List<String> labels, int y) {
        int x = BankLayout.EDGE;
        for (String label : labels) {
            int width = buttonWidth(label);
            addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> open(pageFor(label)))
                    .dimensions(x, y, width, BankLayout.BUTTON_HEIGHT).build());
            x += width + BankLayout.GAP;
        }
    }

    private void addClose() {
        int width = buttonWidth(BankChrome.CLOSE);
        addDrawableChild(ButtonWidget.builder(Text.literal(BankChrome.CLOSE), button -> close())
                .dimensions(this.width - BankLayout.EDGE - width, BankLayout.closeY(this.height), width, BankLayout.BUTTON_HEIGHT).build());
    }

    private BankLayout.Band pageBand;
    private BankLayout.ManagementPane managementPane;
    private boolean managementBookBeside;

    private void initMoney(List<BankSnapshot.MoneyRow> rows, boolean includeAll, String verb) {
        ensurePay(rows);
        useControls(1);
        placeDrawer("pay", centerX(listWidth()), pageBand.listTop(), listWidth(), moneyChoices(rows), payId);
        addAmountBlock(includeAll, verb);
    }

    private void initExchange() {
        List<ChoiceListWidget.Choice> rows = exchangeChoices();
        ensureExchangeIds(rows);
        useControls(1);
        int[] columns = twoColumns();
        placeDrawer("pay", columns[0], pageBand.listTop(), columns[2], rows, payId);
        placeDrawer("receive", columns[1], pageBand.listTop(), columns[2], rows, receiveId);
        addAmountBlock(false, "确认兑换");
    }

    private void initIssue() {
        List<BankSnapshot.MoneyRow> rows = BankSnapshot.currencies(data);
        ensurePay(rows);
        useControls(1);
        int[] columns = twoColumns();
        placeDrawer("pay", columns[0], pageBand.listTop(), columns[2], moneyChoices(rows), payId);
        placeDrawer("payee", columns[1], pageBand.listTop(), columns[2], List.of(
                new ChoiceListWidget.Choice("bearer", "持票人兑付"),
                new ChoiceListWidget.Choice("self", "仅本人兑付")), payeeSelf ? "self" : "bearer");
        addAmountBlock(false, "确认开立汇票");
    }

    private void initRedeem() {
        useControls(1);
        String label = BankActions.MAIN_HAND_BUTTON;
        int width = buttonWidth(label);
        int x = mainLeft() + Math.max(0, (mainWidth() - width) / 2);
        addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> send(BankActions.presentHeld()))
                .dimensions(x, pageBand.actionY(), width, 20).build());
    }

    private boolean externalPersonalMarket() {
        return "true".equals(BankSnapshot.text(data, "externalPersonalMarket"));
    }

    private void openPersonalCommand(String command) {
        if (client != null) {
            client.setScreen(new ChatScreen(command));
        }
    }

    private void initMarket() {
        boolean external = externalPersonalMarket();
        if (external) {
            marketMode = "redeem";
        }
        List<BankSnapshot.NoteRow> notes = BankSnapshot.notes(data);
        ensureNote(notes);
        List<ChoiceListWidget.Choice> second = noteChoices(notes);
        boolean secondOpen = "note".equals(openDrawer) || "other".equals(openDrawer) || "receive".equals(openDrawer);
        int headerBottom = marketHeaderBottom();
        BankLayout.MarketStack stack = BankLayout.marketStack(headerBottom, BankLayout.contentBottom(this.height),
                !external && "mode".equals(openDrawer), 4, secondOpen, Math.max(1, second.size()));
        pageBand = new BankLayout.Band(stack.firstY(), stack.firstH(), stack.fieldY(), stack.quickY(), stack.actionY());
        marketBalanceY = stack.secondY() + stack.secondH() + 4;
        if (external) {
            int[] columns = twoColumns();
            addDrawableChild(textButton("前往市场", columns[0], stack.firstY(), () -> openPersonalCommand("/market note")));
            addDrawableChild(textButton("转给玩家", columns[1], stack.firstY(),
                    () -> openPersonalCommand("/bank note transfer " + (noteName.isBlank() ? "" : "\"" + noteName + "\" "))));
        } else {
            placeDrawer("mode", centerX(listWidth()), stack.firstY(), listWidth(), List.of(
                    new ChoiceListWidget.Choice("buy", "买入"),
                    new ChoiceListWidget.Choice("sell", "卖出"),
                    new ChoiceListWidget.Choice("swap", "互换"),
                    new ChoiceListWidget.Choice("redeem", "通兑")), marketMode, stack.firstH());
        }
        if ("swap".equals(marketMode)) {
            int[] columns = twoColumns();
            placeDrawer("note", columns[0], stack.secondY(), columns[2], second, noteName, stack.secondH());
            placeDrawer("other", columns[1], stack.secondY(), columns[2], second, otherNote, stack.secondH());
        } else if ("redeem".equals(marketMode)) {
            List<BankSnapshot.MoneyRow> rows = BankSnapshot.hardCurrencies(data);
            if (findMoney(rows, receiveId) == null) {
                receiveId = currencyId(rows, "银行券", "note");
            }
            int[] columns = twoColumns();
            placeDrawer("note", columns[0], stack.secondY(), columns[2], second, noteName, stack.secondH());
            placeDrawer("receive", columns[1], stack.secondY(), columns[2], moneyChoices(rows), receiveId, stack.secondH());
        } else {
            placeDrawer("note", centerX(listWidth()), stack.secondY(), listWidth(), second, noteName, stack.secondH());
        }
        addAmountBlock(false, marketVerb());
    }

    private void initIssuance() {
        BankLayout.Box port = issuancePort();
        IssuancePane pane = new IssuancePane(port.x(), port.y(), port.width(), port.height(), this::toggleIssuanceDrawer, this::pickIssuanceDrawer);
        issuancePane = pane;
        int left = port.x() + 4;
        int right = port.right() - 8;
        int textWidth = Math.max(20, right - left);
        int y = port.y() + 4;
        if (!BankSnapshot.hasPersonal(data)) {
            y = placeScrollDrawer(pane, "regime", left, right, y, List.of(
                    new ChoiceListWidget.Choice("float", "浮动汇率制"),
                    new ChoiceListWidget.Choice("peg", "联系汇率制"),
                    new ChoiceListWidget.Choice("hard", "固定汇率制")), "float");
            y += 6;
            y = paneText(pane, "还没有记名。点选一种发行制度后，聊天框会先说明要输入个人银行券的名字。名字写在命令后面，再按回车。",
                    left, y, textWidth, 0xFFE6E6E6);
            for (String rule : BankRegimes.rules()) {
                y = paneText(pane, rule, left, y, textWidth, 0xFFAAAAAA);
            }
            pane.finish(y);
            pane.setScroll(issuanceScroll);
            return;
        }
        if (!notice.isBlank()) {
            y = paneText(pane, notice, left, y, textWidth, 0xFFFFCC88);
            y += 4;
        }
        y = paneButtons(pane, left, right, y, List.of("宣告破产"), List.of(() -> {
            send(BankActions.noteBankrupt());
            page = Page.CONFIRM;
            client.execute(this::clearAndInit);
        }));
        y += 4;
        y = paneButtons(pane, left, right, y, List.of("查看能否切换发行制度"), List.of(() -> open(Page.ISSUANCE_SWITCH)));
        y += 6;
        String regimeName = BankSnapshot.regimeName(data);
        y = paneText(pane, "当前发行制度：" + (regimeName == null ? BankBook.MISSING : regimeName), left, y, textWidth, 0xFFE6E6E6);
        JsonObject own = BankSnapshot.ownNote(data);
        String anchor = own == null ? BankSnapshot.text(data, "anchor") : BankSnapshot.text(own, "anchor");
        if (anchor == null) {
            anchor = BankSnapshot.text(data, "anchor");
        }
        y = paneText(pane, "锚：" + (anchor == null ? BankBook.MISSING : anchorName(anchor)), left, y, textWidth, 0xFFE6E6E6);
        for (String row : BankRegimes.issuanceHoldings(data)) {
            if (row.startsWith("已锁定")) {
                continue;
            }
            int color = row.contains(BankBook.MISSING) ? 0xFFAAAAAA : 0xFFE8D090;
            y = paneText(pane, row, left, y, textWidth, color);
        }
        if (BankSnapshot.bankrupt(data)) {
            y += 4;
            y = paneText(pane, "这张个人银行券已经破产。", left, y, textWidth, 0xFFFF7777);
            pane.finish(y);
            pane.setScroll(issuanceScroll);
            return;
        }
        y += 6;
        y = addIssuanceAmount(pane, left, right, textWidth, y);
        Boolean paused = BankSnapshot.notePaused(data);
        if (paused != null) {
            y = paneText(pane, paused ? "兑付：已暂停" : "兑付：正常", left, y, textWidth, paused ? 0xFFFF7777 : 0xFF77DD77);
            y += 2;
        }
        String freezeLabel = Boolean.TRUE.equals(paused) ? "恢复兑付" : "暂停兑付";
        y = paneButtons(pane, left, right, y, List.of("按数量增发", "按数量收回"), List.of(
                () -> sendAmount(BankActions::issueUnbacked),
                () -> sendAmount(BankActions::noteRetire)));
        y += 4;
        y = paneButtons(pane, left, right, y, List.of(freezeLabel), List.of(
                () -> send(Boolean.TRUE.equals(paused) ? BankActions.noteUnfreeze() : BankActions.noteFreeze())));
        y += 4;
        y = paneButtons(pane, left, right, y, List.of("存入央行准备金", "取出央行准备金"), List.of(
                () -> sendAmount(BankActions::reserveDeposit),
                () -> sendAmount(BankActions::reserveWithdraw)));
        y += 4;
        y = paneButtons(pane, left, right, y, List.of("按准备金发行", "确认切换"), List.of(
                () -> sendAmount(BankActions::noteIssue),
                this::confirmSwitch));
        y += 8;
        String rule = BankRegimes.FLOAT_RULE;
        if (regimeName != null && regimeName.contains("联系")) {
            rule = BankRegimes.PEG_RULE;
        } else if (regimeName != null && regimeName.contains("固定")) {
            rule = BankRegimes.HARD_RULE;
        }
        y = paneText(pane, rule, left, y, textWidth, 0xFFAAAAAA);
        y += 6;
        rememberIssuanceChoice();
        List<BankRegimes.AnchorRow> anchors = BankRegimes.anchorRows(data);
        y = placeScrollDrawer(pane, "switchRegime", left, right, y, List.of(
                new ChoiceListWidget.Choice("float", "浮动汇率制"),
                new ChoiceListWidget.Choice("peg", "联系汇率制"),
                new ChoiceListWidget.Choice("hard", "固定汇率制")), switchRegime);
        y += 6;
        List<ChoiceListWidget.Choice> anchorChoices = new ArrayList<>();
        for (BankRegimes.AnchorRow row : anchors) {
            anchorChoices.add(new ChoiceListWidget.Choice(row.id(), row.label(), row.group()));
        }
        y = placeScrollDrawer(pane, "switchAnchor", left, right, y, anchorChoices, switchAnchor);
        pane.finish(y);
        pane.setScroll(issuanceScroll);
    }

    private int addIssuanceAmount(IssuancePane pane, int left, int right, int textWidth, int y) {
        int fieldWidth = Math.min(120, textWidth);
        int fieldX = left + Math.max(0, (textWidth - fieldWidth) / 2);
        amount = new TextFieldWidget(textRenderer, fieldX, y, fieldWidth, 16, Text.literal("数量"));
        amount.setMaxLength(32);
        amount.setText(amountText == null || amountText.isBlank() ? "1" : amountText);
        pane.addControl(amount, fieldX, y);
        y += 20;
        List<String> chips = List.of("1", "5", "10", "64");
        List<Integer> chipWidths = List.of(28, 28, 28, 28);
        int chipsTotal = 28 * 4 + BankLayout.GAP * 3;
        int chipsX = left + Math.max(0, (textWidth - chipsTotal) / 2);
        BankLayout.Flow chipFlow = BankLayout.flow(chipsX, y, right, BankLayout.GAP, 18, chips, chipWidths);
        for (BankLayout.Box box : chipFlow.boxes()) {
            ButtonWidget chip = ButtonWidget.builder(Text.literal(box.label()), button -> quick(box.label()))
                    .dimensions(box.x(), box.y(), box.width(), box.height()).build();
            pane.addControl(chip, box.x(), box.y());
        }
        return chipFlow.bottom() + 4;
    }

    private void toggleIssuanceDrawer(String role) {
        rememberAmount();
        if (issuancePane != null) {
            issuanceScroll = issuancePane.scroll();
        }
        openDrawer = role.equals(openDrawer) ? "" : role;
        if (client != null) {
            client.execute(this::clearAndInit);
        }
    }

    private void pickIssuanceDrawer(String role, ChoiceListWidget widget) {
        onList(new Placed(role, widget, 0, 0, 0, 0, true));
        if (!widget.isDrawer()) {
            return;
        }
        openDrawer = "";
        if ("regime".equals(role) || client == null) {
            return;
        }
        rememberAmount();
        if (issuancePane != null) {
            issuanceScroll = issuancePane.scroll();
        }
        client.execute(this::clearAndInit);
    }

    private int paneButtons(IssuancePane pane, int left, int right, int y, List<String> labels, List<Runnable> actions) {
        List<Integer> widths = new ArrayList<>();
        for (String label : labels) {
            widths.add(buttonWidth(label));
        }
        BankLayout.Flow flow = BankLayout.flow(left, y, right, 4, 18, labels, widths);
        for (int i = 0; i < flow.boxes().size(); i++) {
            BankLayout.Box box = flow.boxes().get(i);
            Runnable action = actions.get(i);
            ButtonWidget widget = ButtonWidget.builder(Text.literal(box.label()), button -> action.run())
                    .dimensions(box.x(), box.y(), box.width(), box.height()).build();
            pane.addControl(widget, box.x(), box.y());
        }
        return flow.bottom();
    }

    private int paneText(IssuancePane pane, String text, int x, int y, int width, int color) {
        for (String line : BankText.lines(text, width, textRenderer::getWidth)) {
            pane.addLine(line, x, y, color);
            y += 12;
        }
        return y;
    }

    private int placeScrollDrawer(IssuancePane pane, String role, int left, int right, int y,
                                  List<ChoiceListWidget.Choice> choices, String selected) {
        boolean open = role.equals(openDrawer);
        int drawerWidth = Math.min(180, Math.max(80, right - left));
        int drawerX = left + Math.max(0, (right - left - drawerWidth) / 2);
        int drawerHeight = BankLayout.drawerHeight(open, Math.max(1, choices.size()));
        ChoiceListWidget widget = new ChoiceListWidget(client, drawerX, y, drawerWidth, drawerHeight);
        widget.setChoices(choices);
        widget.select(selected);
        widget.setDrawer(true);
        widget.setExpanded(open);
        pane.addDrawer(role, widget, drawerX, y, drawerWidth, drawerHeight);
        placed.add(new Placed(role, widget, drawerX, y, drawerWidth, drawerHeight, true));
        return y + drawerHeight;
    }

    private BankLayout.Box issuancePort() {
        return BankLayout.issuancePort(this.width, this.height, issuanceTitleHeight());
    }

    private int issuanceTitleHeight() {
        if (page == Page.ISSUANCE_SWITCH) {
            return 16;
        }
        String personal = BankSnapshot.text(data, "personal");
        if (!BankSnapshot.hasPersonal(data) || personal == null || personal.isBlank()) {
            return 16;
        }
        int titleWidth = textRenderer.getWidth("个人发行");
        int noteWidth = textRenderer.getWidth("「" + personal + "」");
        if (titleWidth + 8 + noteWidth <= mainWidth()) {
            return 16;
        }
        return 28;
    }

    private void rememberIssuanceChoice() {
        if (switchRegime == null || switchRegime.isBlank()) {
            switchRegime = BankActions.regimeToken(BankSnapshot.regimeName(data));
        }
        if (switchAnchor == null || switchAnchor.isBlank()) {
            JsonObject note = BankSnapshot.ownNote(data);
            String raw = note == null ? BankSnapshot.text(data, "anchor") : BankSnapshot.text(note, "anchor");
            if (raw == null) {
                raw = BankSnapshot.text(data, "anchor");
            }
            String token = BankActions.anchorToken(raw);
            if (token != null) {
                switchAnchor = token;
            }
        }
    }

    private void confirmSwitch() {
        String regime = selected("switchRegime", switchRegime);
        String anchor = selected("switchAnchor", switchAnchor);
        if (regime == null || regime.isBlank() || anchor == null || anchor.isBlank() || anchor.startsWith("group:")) {
            showNotice("请先选择发行制度和锚。");
            return;
        }
        switchRegime = regime;
        switchAnchor = anchor;
        send(BankActions.noteSwitch(regime, anchor));
    }

    private void useControls(int drawerRows) {
        BankLayout.PlayerControls controls = BankLayout.playerControls(this.height, drawerRows);
        pageBand = controls.band();
    }

    private void initIssuanceSwitch() {
        if (!BankSnapshot.hasPersonal(data)) {
            page = Page.ISSUANCE;
            return;
        }
        BankLayout.Box port = issuancePort();
        IssuancePane pane = new IssuancePane(port.x(), port.y(), port.width(), port.height(), this::toggleIssuanceDrawer, this::pickIssuanceDrawer);
        issuancePane = pane;
        int left = port.x() + 4;
        int right = port.right() - 8;
        int textWidth = Math.max(20, right - left);
        int y = port.y() + 4;
        y = paneButtons(pane, left, right, y, List.of("返回个人发行"), List.of(() -> open(Page.ISSUANCE)));
        y += 6;
        for (String gate : BankRegimes.gates()) {
            y = paneText(pane, gate, left, y, textWidth, 0xFFAAAAAA);
            y += 2;
        }
        y += 4;
        String blocked = BankRegimes.blockReason(data);
        if (blocked != null) {
            y = paneText(pane, blocked, left, y, textWidth, 0xFFFF7777);
        } else if (!BankRegimes.switchesKnown(data)) {
            y = paneText(pane, BankRegimes.NO_SWITCH, left, y, textWidth, 0xFFAAAAAA);
        } else if (BankRegimes.offers(data).isEmpty()) {
            y = paneText(pane, "柜台没有列出其他发行制度。", left, y, textWidth, 0xFFAAAAAA);
        } else {
            if (!notice.isBlank()) {
                y = paneText(pane, notice, left, y, textWidth, 0xFFFFCC88);
                y += 4;
            }
            for (BankRegimes.Offer offer : BankRegimes.offers(data)) {
                String button = offer.button();
                if (button == null) {
                    y = paneText(pane, offer.refusal(), left, y, textWidth, 0xFFAAAAAA);
                    y += 6;
                    continue;
                }
                y = paneButtons(pane, left, right, y, List.of(button), List.of(() -> send(offer.command())));
                y += 6;
            }
        }
        pane.finish(y);
        pane.setScroll(issuanceScroll);
    }

    /**
     * A collapsed drawer is only its header, so the amount row does not move.
     * Opening it paints the options downward, and stops before that amount row.
     */
    private void placeDrawer(String role, int x, int y, int width, List<ChoiceListWidget.Choice> choices, String selected) {
        placeDrawer(role, x, y, width, choices, selected, -1);
    }

    private void placeDrawer(String role, int x, int y, int width, List<ChoiceListWidget.Choice> choices, String selected, int fixedHeight) {
        boolean open = role.equals(openDrawer);
        int height = fixedHeight > 0 ? fixedHeight : 20;
        if (fixedHeight <= 0 && open) {
            int ceiling = pageBand == null ? y + BankLayout.drawerHeight(true, choices.size()) : pageBand.fieldY() - 12;
            int room = ceiling - y;
            int wanted = BankLayout.drawerHeight(true, choices.size());
            height = Math.min(wanted, Math.max(20, room));
            if (height < 38 && room >= 38) {
                height = 38;
            }
        }
        place(role, x, y, width, height, choices, selected);
    }

    private String adjustName() {
        String name = currencyName(selected("pay", payId));
        return name == null || name.isBlank() ? "未选择货币" : name;
    }

    private String currentAmount() {
        if (amount != null && amount.getText() != null && !amount.getText().isBlank()) {
            return amount.getText().trim();
        }
        return amountText == null || amountText.isBlank() ? "1" : amountText.trim();
    }

    private void initConfirm() {
        int y = contentTop() + 28;
        int back = buttonWidth("返回");
        int confirm = buttonWidth("确认宣告破产");
        int start = mainLeft() + Math.max(0, (mainWidth() - back - confirm - 8) / 2);
        addDrawableChild(ButtonWidget.builder(Text.literal("返回"), button -> {
            send(BankActions.noteBankruptNo());
            page = Page.ISSUANCE;
            client.execute(this::clearAndInit);
        }).dimensions(start, y, back, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("确认宣告破产"), button -> send(BankActions.noteBankruptYes()))
                .dimensions(start + back + 8, y, confirm, 20).build());
    }

    private void addAmountBlock(boolean includeAll, String verb) {
        if (pageBand == null) {
            pageBand = BankLayout.band(contentTop(), BankLayout.contentBottom(this.height), 28);
        }
        int fieldY = pageBand.fieldY();
        int quickY = pageBand.quickY();
        int actionY = pageBand.actionY();
        int fieldWidth = Math.min(120, Math.max(70, mainWidth() / 3));
        amount = new TextFieldWidget(textRenderer, centerX(fieldWidth), fieldY, fieldWidth, 16, Text.literal("数量"));
        amount.setMaxLength(32);
        amount.setText(amountText == null || amountText.isBlank() ? "1" : amountText);
        addDrawableChild(amount);
        List<String> labels = new ArrayList<>(List.of("1", "5", "10", "64"));
        if (includeAll) {
            labels.add("全部");
        }
        int total = 0;
        int[] widths = new int[labels.size()];
        for (int i = 0; i < labels.size(); i++) {
            widths[i] = "全部".equals(labels.get(i)) ? buttonWidth("全部") : 28;
            total += widths[i];
        }
        total += BankLayout.GAP * (labels.size() - 1);
        int x = mainLeft() + Math.max(0, (mainWidth() - total) / 2);
        for (int i = 0; i < labels.size(); i++) {
            String shown = labels.get(i);
            addDrawableChild(ButtonWidget.builder(Text.literal(shown), button -> quick(shown))
                    .dimensions(x, quickY, widths[i], 18).build());
            x += widths[i] + BankLayout.GAP;
        }
        if (verb != null) {
            int width = buttonWidth(verb);
            addDrawableChild(ButtonWidget.builder(Text.literal(verb), button -> submit())
                    .dimensions(centerX(width), actionY, width, 18).build());
        }
    }

    private void addEdgeButton(String label, boolean left, Runnable action) {
        int width = buttonWidth(label);
        int x = left ? mainLeft() : mainRight() - width;
        addDrawableChild(textButton(label, x, BankLayout.contentBottom(this.height) - 20, action));
    }

    private ButtonWidget textButton(String label, int x, int y, Runnable action) {
        return ButtonWidget.builder(Text.literal(label), button -> action.run())
                .dimensions(x, y, buttonWidth(label), 18).build();
    }

    private void place(String role, int x, int y, int width, int height, List<ChoiceListWidget.Choice> choices, String selected) {
        if (width < 40 || height < 16) {
            return;
        }
        ChoiceListWidget widget = new ChoiceListWidget(client, x, y, width, height);
        widget.setChoices(choices);
        widget.select(selected);
        widget.setDrawer(true);
        widget.setExpanded(role.equals(openDrawer));
        addSelectableChild(widget);
        placed.add(new Placed(role, widget, x, y, width, height, false));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        drawColumn(context);
        drawPage(context);
        if (issuancePane != null) {
            issuancePane.render(context, mouseX, mouseY, delta);
        }
        super.render(context, mouseX, mouseY, delta);
        for (Placed item : placed) {
            if (item.inScroll()) {
                continue;
            }
            if (page == Page.EXCHANGE && ("pay".equals(item.role) || "receive".equals(item.role))) {
                context.drawText(textRenderer, "pay".equals(item.role) ? "付出" : "得到", item.x, item.y - 11, 0xFFE8D090, false);
            }
            item.widget.render(context, mouseX, mouseY, delta);
            drawBalanceUnder(context, item);
        }
    }

    private void drawColumn(DrawContext context) {
        int x = BankLayout.EDGE;
        int y = BankLayout.EDGE;
        int width = BankLayout.columnWidth(this.width);
        context.fill(x - 2, BankLayout.EDGE, x + width + 2, BankLayout.contentBottom(this.height), 0x55001018);
        for (String line : BankRates.column(data)) {
            if (y > BankLayout.contentBottom(this.height) - 12) {
                break;
            }
            int color = 0xFFE6E6E6;
            if (line.startsWith("1 钻石") || line.startsWith("1 银行券") || line.startsWith("1 【")) {
                color = 0xFFE8D090;
            } else if (line.contains(BankRates.UNAVAILABLE)) {
                color = 0xFFAAAAAA;
            } else if (BankHeader.showStatus(line)) {
                color = statusColor(line);
            }
            y = drawWrapped(context, line, x, y, width, color);
        }
        int split = BankLayout.columnRight(this.width) + BankLayout.GAP;
        context.fill(split, BankLayout.EDGE, split + 1, BankLayout.contentBottom(this.height), 0x66D0B060);
    }

    private void drawPage(DrawContext context) {
        switch (page) {
            case SAVINGS -> drawSavings(context);
            case DEPOSIT -> drawHeading(context, "存入个人存款", "选择货币后，余额显示在列表下方。");
            case WITHDRAW -> drawHeading(context, "取出个人存款", "选择货币后，余额显示在列表下方。");
            case EXCHANGE -> drawHeading(context, "硬通货兑换", "按牌价在账户之间兑换。这不是通兑，不动发行人的准备金。");
            case ISSUE -> drawHeading(context, "开立汇票", "选择币种和抬头，再填写开出的数量。");
            case REDEEM -> drawRedeem(context);
            case MARKET -> drawMarketHeading(context);
            case ISSUANCE -> drawIssuanceTitle(context);
            case ISSUANCE_SWITCH -> drawSwitchTitle(context);
            case MANAGEMENT -> drawManagement(context);
            case MANAGEMENT_ADJUST -> {
                drawHeading(context, "货币调整", "准备金和央行储蓄在总账里逐项列出，不随这里的选择改变。");
                if (pageBand != null) {
                    context.drawText(textRenderer, "数值管理", mainLeft(), pageBand.fieldY() - 12, 0xFFE8D090, false);
                }
            }
            case CONFIRM -> context.drawText(textRenderer,
                    "确认后才会作废「" + safe(BankSnapshot.text(data, "personal")) + "」。返回则账目不变。",
                    mainLeft(), contentTop() + 8, 0xFFFF7777, false);
            default -> {
            }
        }
        if (!notice.isBlank() && page != Page.CONFIRM && page != Page.ISSUANCE && page != Page.ISSUANCE_SWITCH) {
            if (page != Page.MARKET) {
                context.drawText(textRenderer, notice, mainLeft(), contentTop(), 0xFFFFCC88, false);
            } else {
                drawWrapped(context, notice, mainLeft(), marketHeaderBottom(), mainWidth(), 0xFFFFCC88);
            }
        }
    }

    private void drawSavings(DrawContext context) {
        drawCentered(context, BankChrome.SAVINGS_TITLE, contentTop(), 0xFFD0B060);
        drawCentered(context, "这是你自己的存款。", contentTop() + 14, 0xFFAAAAAA);
        int gap = 16;
        int column = Math.max(70, (mainWidth() - gap) / 2);
        int start = mainLeft() + Math.max(0, (mainWidth() - column * 2 - gap) / 2);
        int titleY = contentTop() + 34;
        context.drawText(textRenderer, "个人存款", start, titleY, 0xFFD0B060, false);
        context.drawText(textRenderer, "持有的个人银行券", start + column + gap, titleY, 0xFFD0B060, false);
        int left = titleY + 16;
        int right = titleY + 16;
        List<BankSnapshot.MoneyRow> currencies = BankSnapshot.currencies(data);
        if (currencies.isEmpty()) {
            context.drawText(textRenderer, "柜台还没有发来个人存款。", start, left, 0xFFAAAAAA, false);
        }
        for (BankSnapshot.MoneyRow row : currencies) {
            left = drawWrapped(context, row.name(), start, left, column, 0xFFE6E6E6);
            left = drawWrapped(context, BankSnapshot.balancePhrase(row.amount()), start, left, column, 0xFFB7C7B0);
            left += 4;
        }
        List<BankSnapshot.NoteRow> held = BankSnapshot.heldNotes(data);
        int noteX = start + column + gap;
        if (held.isEmpty()) {
            context.drawText(textRenderer, "没有持有个人银行券。", noteX, right, 0xFFAAAAAA, false);
            return;
        }
        for (BankSnapshot.NoteRow note : held) {
            right = drawWrapped(context, note.name(), noteX, right, column, 0xFFE6E6E6);
            right = drawWrapped(context, "账面余额：" + note.balance(), noteX, right, column, 0xFFB7C7B0);
            right = drawWrapped(context, note.price() == null ? BankRates.UNAVAILABLE : "现价：" + note.price(),
                    noteX, right, column, note.price() == null ? 0xFFAAAAAA : 0xFFE8D090);
            right += 4;
        }
    }

    private void drawIssuanceTitle(DrawContext context) {
        int x = mainLeft();
        int y = contentTop();
        context.drawText(textRenderer, "个人发行", x, y, 0xFFD0B060, false);
        String personal = BankSnapshot.text(data, "personal");
        if (!BankSnapshot.hasPersonal(data) || personal == null || personal.isBlank()) {
            return;
        }
        String note = "「" + personal + "」";
        int noteX = x + textRenderer.getWidth("个人发行") + 8;
        if (noteX + textRenderer.getWidth(note) <= mainRight()) {
            context.drawText(textRenderer, note, noteX, y, 0xFFE8D090, false);
        } else {
            context.drawText(textRenderer, note, x, y + 12, 0xFFE8D090, false);
        }
    }

    private void drawSwitchTitle(DrawContext context) {
        context.drawText(textRenderer, "切换发行制度", mainLeft(), contentTop(), 0xFFD0B060, false);
    }

    private void drawManagement(DrawContext context) {
        context.drawText(textRenderer, BankChrome.MANAGEMENT, mainLeft(), contentTop(), 0xFFD0B060, false);
        int top = contentTop() + 16;
        int bottom = BankLayout.contentBottom(this.height) - 28;
        bookX = mainLeft();
        bookY = top;
        bookW = mainWidth();
        bookH = Math.max(36, bottom - top);
        List<String> lines = BankBook.visibleLedger(data);
        int maxScroll = Math.max(0, lines.size() * 12 - (bookH - 8));
        if (bookScroll > maxScroll) {
            bookScroll = maxScroll;
        }
        drawFramedLines(context, lines, bookX, bookY, bookW, bookH, bookScroll);
    }

    private void drawFramedLines(DrawContext context, List<String> lines, int x, int y, int width, int height, int scroll) {
        context.fill(x, y, x + width, y + height, 0xF0101014);
        int innerLeft = x + 1;
        int innerTop = y + 1;
        int innerRight = x + width - 1;
        int innerBottom = y + height - 1;
        if (innerRight > innerLeft && innerBottom > innerTop) {
            context.enableScissor(innerLeft, innerTop, innerRight, innerBottom);
            int lineY = y + 4 - scroll;
            for (String line : lines) {
                if (lineY > innerBottom) {
                    break;
                }
                if (lineY + 12 > innerTop) {
                    int color = line.contains(BankBook.MISSING) || line.contains("暂无牌价") ? 0xFFAAAAAA : 0xFFE6E6E6;
                    if (line.equals(BankBook.CIRCULATION_NOTE)) {
                        color = 0xFFAAAAAA;
                    }
                    context.drawText(textRenderer, line, x + 4, lineY, color, false);
                }
                lineY += 12;
            }
            context.disableScissor();
        }
        context.fill(x, y, x + width, y + 1, 0xFFC8A060);
        context.fill(x, y + height - 1, x + width, y + height, 0xFFC8A060);
        context.fill(x, y, x + 1, y + height, 0xFFC8A060);
        context.fill(x + width - 1, y, x + width, y + height, 0xFFC8A060);
    }

    private void drawRedeem(DrawContext context) {
        int y = contentTop() + 8;
        context.drawText(textRenderer, "兑付汇票", mainLeft(), y, 0xFFD0B060, false);
        drawWrapped(context, BankActions.MAIN_HAND_BILL, mainLeft(), y + 16, mainWidth(), 0xFFE6E6E6);
    }

    /** Title, then the full help line, both above the drawers. */
    private int marketHeaderBottom() {
        int y = contentTop() + 12;
        String help = marketHeading();
        int width = Math.max(40, mainWidth());
        String rest = help == null ? "" : help;
        while (!rest.isEmpty()) {
            String part = rest;
            while (textRenderer.getWidth(part) > width && part.length() > 1) {
                part = part.substring(0, part.offsetByCodePoints(0, part.codePointCount(0, part.length()) - 1));
            }
            y += 12;
            if (part.equals(rest)) {
                break;
            }
            rest = rest.substring(part.length());
        }
        return y + 6;
    }

    private void drawMarketHeading(DrawContext context) {
        int x = mainLeft();
        int y = contentTop();
        context.drawText(textRenderer, "个人银行券交易", x, y, 0xFFD0B060, false);
        drawFitted(context, marketHeading(), x, y + 12, mainWidth(), 0xFFAAAAAA);
    }

    private int drawFitted(DrawContext context, String text, int x, int y, int width, int color) {
        String rest = text == null ? "" : text;
        int limit = Math.max(40, width);
        while (!rest.isEmpty()) {
            String part = rest;
            while (textRenderer.getWidth(part) > limit && part.length() > 1) {
                part = part.substring(0, part.offsetByCodePoints(0, part.codePointCount(0, part.length()) - 1));
            }
            context.drawText(textRenderer, part, x, y, color, false);
            y += 12;
            if (part.equals(rest)) {
                break;
            }
            rest = rest.substring(part.length());
        }
        return y;
    }

    private void drawHeading(DrawContext context, String title, String copy) {
        context.drawText(textRenderer, title, mainLeft(), contentTop(), 0xFFD0B060, false);
        if (copy == null || copy.isBlank()) {
            return;
        }
        int x = mainLeft() + textRenderer.getWidth(title) + 8;
        if (x + textRenderer.getWidth(copy) <= mainRight()) {
            context.drawText(textRenderer, copy, x, contentTop(), 0xFFAAAAAA, false);
        }
    }

    private void drawCentered(DrawContext context, String text, int y, int color) {
        int width = textRenderer.getWidth(text);
        context.drawText(textRenderer, text, mainLeft() + Math.max(0, (mainWidth() - width) / 2), y, color, false);
    }

    private void drawBalanceUnder(DrawContext context, Placed item) {
        if (!showsBalance(item.role)) {
            return;
        }
        int textY = marketBalanceY >= 0 ? marketBalanceY : item.y + item.height + 2;
        String line = balanceCaption(item) + BankSnapshot.balancePhrase(balanceFor(item.role, item.widget.selectedId()));
        context.drawText(textRenderer, line, item.x, textY, 0xFFB7C7B0, false);
        if ("note".equals(item.role)) {
            BankSnapshot.NoteRow note = findNote(item.widget.selectedId());
            String quote = note == null || note.price() == null ? BankRates.UNAVAILABLE : "现价：" + note.price();
            context.drawText(textRenderer, quote, item.x, textY + 12, note == null || note.price() == null ? 0xFFAAAAAA : 0xFFE8D090, false);
        }
    }

    private static String anchorName(String anchor) {
        String token = BankActions.anchorToken(anchor);
        if (token == null) {
            return anchor == null ? BankBook.MISSING : anchor;
        }
        return switch (token) {
            case "diamond" -> "钻石";
            case "copper" -> "铜锭";
            default -> "银行券";
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (issuancePane != null && issuancePane.contains(mouseX, mouseY)) {
            issuancePane.mouseClicked(mouseX, mouseY, button);
            if (amount != null) {
                amount.setFocused(amount.isMouseOver(mouseX, mouseY));
            }
            return true;
        }
        if (amount != null && issuancePane != null) {
            amount.setFocused(false);
        }
        for (var child : children()) {
            if (child instanceof ChoiceListWidget) {
                continue;
            }
            if (child.isMouseOver(mouseX, mouseY) && child.mouseClicked(mouseX, mouseY, button)) {
                setFocused(child);
                return true;
            }
        }
        for (int i = placed.size() - 1; i >= 0; i--) {
            Placed item = placed.get(i);
            if (item.inScroll()) {
                continue;
            }
            int visualH = item.widget.isDrawer() && !item.widget.expanded() ? ChoiceListWidget.HEADER : item.height;
            if (mouseX < item.x || mouseX >= item.x + item.width || mouseY < item.y || mouseY >= item.y + visualH) {
                continue;
            }
            if (item.widget.isDrawer() && mouseY < item.y + ChoiceListWidget.HEADER) {
                openDrawer = item.role.equals(openDrawer) ? "" : item.role;
                if (client != null) {
                    client.execute(this::clearAndInit);
                }
                return true;
            }
            if (item.widget.mouseClicked(mouseX, mouseY, button) && item.widget.pollPick()) {
                onList(item);
                if (item.widget.isDrawer()) {
                    openDrawer = "";
                    if (!"regime".equals(item.role) && client != null) {
                        client.execute(this::clearAndInit);
                    }
                }
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (issuancePane != null && issuancePane.contains(mouseX, mouseY)) {
            return issuancePane.mouseScrolled(mouseX, mouseY, amount);
        }
        for (Placed item : placed) {
            if (item.inScroll()) {
                continue;
            }
            if (item.widget.mouseScrolled(mouseX, mouseY, amount)) {
                return true;
            }
        }
        if (page == Page.MANAGEMENT && mouseX >= bookX && mouseX < bookX + bookW && mouseY >= bookY && mouseY < bookY + bookH) {
            int lines = BankBook.visibleLedger(data).size();
            int maxScroll = Math.max(0, lines * 12 - (bookH - 8));
            bookScroll = Math.max(0, Math.min(maxScroll, bookScroll - (int) Math.round(amount * 12)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (issuancePane != null && issuancePane.mouseDragged(mouseX, mouseY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (issuancePane != null) {
            issuancePane.mouseReleased();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (amount != null && issuancePane != null && amount.isFocused()) {
            amount.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (amount != null && issuancePane != null && amount.isFocused()) {
            return amount.charTyped(chr, modifiers);
        }
        return super.charTyped(chr, modifiers);
    }

    private void onList(Placed item) {
        String id = item.widget.selectedId();
        if (id == null) {
            return;
        }
        switch (item.role) {
            case "pay" -> payId = id;
            case "receive" -> receiveId = id;
            case "note" -> noteName = id;
            case "other" -> otherNote = id;
            case "mode" -> {
                if (!id.equals(marketMode)) {
                    marketMode = id;
                    if ("redeem".equals(id)) {
                        receiveId = currencyId(BankSnapshot.hardCurrencies(data), "银行券", "note");
                    }
                }
            }
            case "payee" -> payeeSelf = "self".equals(id);
            case "regime" -> askForNoteName(id);
            case "switchRegime" -> switchRegime = id;
            case "switchAnchor" -> {
                if (!id.startsWith("group:")) {
                    switchAnchor = id;
                }
            }
            default -> {
            }
        }
    }

    private void askForNoteName(String regime) {
        String prompt = BankActions.noteNamePrompt(regime);
        notice = prompt;
        if (client != null && client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(Text.literal(prompt));
        }
        if (client != null) {
            client.setScreen(new ChatScreen(BankActions.noteNameCommand(regime)));
        }
    }

    private void open(Page next) {
        captureSelections();
        if (issuancePane != null) {
            issuanceScroll = issuancePane.scroll();
        }
        if (next != page) {
            openDrawer = "";
            bookScroll = 0;
            issuanceScroll = 0;
        }
        page = next;
        notice = "";
        client.execute(this::clearAndInit);
    }

    private void quick(String shown) {
        amountText = shown;
        if (amount != null) {
            amount.setText(shown);
        }
        submit();
    }

    private void submit() {
        captureSelections();
        String quantity = QuantityTokens.token(amountText);
        switch (page) {
            case DEPOSIT -> sendIfPresent(selected("pay", payId), () -> BankActions.deposit(selected("pay", payId), quantity));
            case WITHDRAW -> {
                String id = selected("pay", payId);
                String refusal = BankFunds.withdrawalRefusal(data, id, currencyName(id), quantity);
                if (refusal != null) {
                    notice = refusal;
                } else {
                    sendIfPresent(id, () -> BankActions.withdraw(id, quantity));
                }
            }
            case EXCHANGE -> submitExchange(quantity);
            case ISSUE -> sendIfPresent(selected("pay", payId), () -> BankActions.check(selected("pay", payId), quantity, checkSelf()));
            case REDEEM -> send(BankActions.presentHeld());
            case MARKET -> submitMarket(quantity);
            default -> {
            }
        }
    }

    private void submitExchange(String quantity) {
        String pay = selected("pay", payId);
        String receive = selected("receive", receiveId);
        if (pay.isBlank() || receive.isBlank()) {
            notice = "请选择付出的货币和得到的货币。";
            return;
        }
        if (pay.equals(receive)) {
            notice = "付出和得到不能是同一种。";
            return;
        }
        String refusal = BankFunds.physicalExchangeRefusal(data, pay, currencyName(pay), quantity);
        if (refusal != null) {
            notice = refusal;
            return;
        }
        String command = BankExchange.command(pay, receive, quantity);
        if (command == null) {
            notice = BankExchange.REFUSAL;
            return;
        }
        send(command);
    }

    private void submitMarket(String quantity) {
        String note = selected("note", noteName);
        if (note.isBlank()) {
            notice = "请选择一张个人银行券。";
            return;
        }
        switch (marketMode) {
            case "sell" -> send(BankActions.noteSell(note, quantity));
            case "swap" -> {
                String other = selected("other", otherNote);
                if (other.isBlank() || other.equals(note)) {
                    notice = "请再选择一张要换成的个人银行券。";
                    return;
                }
                send(BankActions.noteSwap(note, other, quantity));
            }
            case "redeem" -> {
                String payout = selected("receive", receiveId);
                if (payout.isBlank()) {
                    notice = "请选择通兑成的货币。";
                    return;
                }
                send(BankActions.noteRedeem(note, payout, quantity));
            }
            default -> send(BankActions.noteBuy(note, quantity));
        }
    }

    private void sendIfPresent(String id, java.util.function.Supplier<String> action) {
        if (id == null || id.isBlank()) {
            notice = "请先在列表中选择一项。";
            return;
        }
        send(action.get());
    }

    private void sendAmount(java.util.function.Function<String, String> action) {
        captureSelections();
        String quantity = QuantityTokens.token(amountText);
        if ("all".equals(quantity)) {
            showNotice("这里不能使用全部，请填写数量。");
            return;
        }
        send(action.apply(quantity));
    }

    private void send(String action) {
        notice = "";
        BankUiClient.send(action);
    }

    private void showNotice(String text) {
        notice = text;
        if (issuancePane == null || client == null) {
            return;
        }
        issuanceScroll = 0;
        client.execute(this::clearAndInit);
    }

    private void captureSelections() {
        rememberAmount();
        payId = selected("pay", payId);
        receiveId = selected("receive", receiveId);
        noteName = selected("note", noteName);
        otherNote = selected("other", otherNote);
        String mode = selected("mode", marketMode);
        if (!mode.isBlank()) {
            marketMode = mode;
        }
        String payee = selected("payee", "");
        if ("self".equals(payee)) {
            payeeSelf = true;
        } else if ("bearer".equals(payee)) {
            payeeSelf = false;
        }
    }

    private void rememberAmount() {
        if (amount != null) {
            amountText = amount.getText();
        }
    }

    private String selected(String role, String fallback) {
        Placed item = placed(role);
        if (item != null && item.widget.selectedId() != null) {
            return item.widget.selectedId();
        }
        return fallback == null ? "" : fallback;
    }

    private Placed placed(String role) {
        for (Placed item : placed) {
            if (item.role.equals(role)) {
                return item;
            }
        }
        return null;
    }

    private void ensurePay(List<BankSnapshot.MoneyRow> rows) {
        if (findMoney(rows, payId) == null) {
            payId = rows.isEmpty() ? "" : rows.get(0).id();
        }
    }

    private void ensureReceive(List<BankSnapshot.MoneyRow> rows) {
        if (findMoney(rows, receiveId) != null && !receiveId.equals(payId)) {
            return;
        }
        for (BankSnapshot.MoneyRow row : rows) {
            if (!row.id().equals(payId)) {
                receiveId = row.id();
                return;
            }
        }
        receiveId = payId;
    }

    private void ensureNote(List<BankSnapshot.NoteRow> notes) {
        if (notes.isEmpty()) {
            noteName = "";
            otherNote = "";
            return;
        }
        if (findNote(noteName) == null) {
            noteName = notes.get(0).name();
        }
        if (notes.size() > 1 && (findNote(otherNote) == null || otherNote.equals(noteName))) {
            for (BankSnapshot.NoteRow note : notes) {
                if (!note.name().equals(noteName)) {
                    otherNote = note.name();
                    return;
                }
            }
        }
    }

    private boolean checkSelf() {
        String payee = selected("payee", "");
        if ("self".equals(payee)) {
            return true;
        }
        if ("bearer".equals(payee)) {
            return false;
        }
        return payeeSelf;
    }

    private String balanceFor(String role, String id) {
        if (BankExchange.personal(id) || "note".equals(role) || "other".equals(role)) {
            String name = BankExchange.personal(id) ? BankExchange.noteName(id) : id;
            BankSnapshot.NoteRow note = findNote(name);
            return note == null ? null : note.balance();
        }
        BankSnapshot.MoneyRow money = findMoney(BankSnapshot.currencies(data), id);
        return money == null ? null : money.amount();
    }

    private String balanceCaption(Placed item) {
        boolean paired = page == Page.EXCHANGE
                || (page == Page.MARKET && "swap".equals(marketMode))
                || (page == Page.MARKET && "redeem".equals(marketMode) && "receive".equals(item.role));
        return paired ? caption(item.role) : "";
    }

    private static String caption(String role) {
        return switch (role) {
            case "pay" -> "付出  ";
            case "receive" -> "得到  ";
            case "other" -> "换成  ";
            case "note" -> "个人银行券  ";
            default -> "";
        };
    }

    private boolean showsBalance(String role) {
        if (page == Page.MANAGEMENT) {
            return false;
        }
        return "pay".equals(role) || "receive".equals(role) || "note".equals(role) || "other".equals(role);
    }

    private String marketHeading() {
        if (externalPersonalMarket()) {
            return "挂牌买卖在市场办理。这里可以转账或从发行准备金通兑。";
        }
        return switch (marketMode) {
            case "sell" -> "卖出个人银行券，只换成银行券，不动发行人的准备金。";
            case "swap" -> "把一张个人银行券换成另一张，不动发行人的准备金。";
            case "redeem" -> "通兑按该券的规则兑付发行人的准备金，不是同一账户里的兑换。";
            default -> "买入个人银行券，只用银行券支付，不动发行人的准备金。";
        };
    }

    private String marketVerb() {
        return switch (marketMode) {
            case "sell" -> "确认卖出";
            case "swap" -> "确认互换";
            case "redeem" -> "确认通兑";
            default -> "确认买入";
        };
    }

    private String currencyName(String id) {
        BankSnapshot.MoneyRow money = findMoney(BankSnapshot.currencies(data), id);
        return money == null ? "" : money.name();
    }

    private List<ChoiceListWidget.Choice> moneyChoices(List<BankSnapshot.MoneyRow> rows) {
        List<ChoiceListWidget.Choice> choices = new ArrayList<>();
        for (BankSnapshot.MoneyRow row : rows) {
            choices.add(new ChoiceListWidget.Choice(row.id(), row.name()));
        }
        return choices;
    }

    private List<ChoiceListWidget.Choice> noteChoices(List<BankSnapshot.NoteRow> notes) {
        List<ChoiceListWidget.Choice> choices = new ArrayList<>();
        for (BankSnapshot.NoteRow note : notes) {
            choices.add(new ChoiceListWidget.Choice(note.name(), note.name()));
        }
        return choices;
    }

    private BankSnapshot.MoneyRow findMoney(List<BankSnapshot.MoneyRow> rows, String id) {
        for (BankSnapshot.MoneyRow row : rows) {
            if (row.id().equals(id)) {
                return row;
            }
        }
        return null;
    }

    private BankSnapshot.NoteRow findNote(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (BankSnapshot.NoteRow note : BankSnapshot.notes(data)) {
            if (name.equals(note.name())) {
                return note;
            }
        }
        return null;
    }

    private static String currencyId(List<BankSnapshot.MoneyRow> rows, String name, String fallback) {
        for (BankSnapshot.MoneyRow row : rows) {
            if (name.equals(row.name()) || fallback.equals(row.id())) {
                return row.id();
            }
        }
        return rows.isEmpty() ? "" : rows.get(0).id();
    }

    private int[] twoColumns() {
        int width = Math.min(150, Math.max(64, (mainWidth() - 12) / 2));
        int total = width * 2 + 12;
        int start = mainLeft() + Math.max(0, (mainWidth() - total) / 2);
        return new int[] {start, start + width + 12, width};
    }

    private int listWidth() {
        return Math.min(180, Math.max(80, mainWidth() - 8));
    }

    private int centerX(int width) {
        return mainLeft() + Math.max(0, (mainWidth() - width) / 2);
    }

    private int mainLeft() {
        return BankLayout.mainLeft(this.width);
    }

    private int mainRight() {
        return BankLayout.mainRight(this.width);
    }

    private int mainWidth() {
        return BankLayout.mainWidth(this.width);
    }

    private int contentTop() {
        return BankLayout.contentTop();
    }

    private int fieldTop() {
        return BankLayout.contentBottom(this.height) - 62;
    }

    private int buttonWidth(String label) {
        return Math.max(BankLayout.labelWidth(label), textRenderer.getWidth(label) + 16);
    }

    private int drawFitted(DrawContext context, String line, int x, int y, int maxWidth, int color, int limitY) {
        String rest = line == null ? "" : line;
        while (!rest.isEmpty() && y + 9 <= limitY) {
            String part = rest;
            while (textRenderer.getWidth(part) > maxWidth && part.length() > 1) {
                part = part.substring(0, part.offsetByCodePoints(0, part.codePointCount(0, part.length()) - 1));
            }
            context.drawText(textRenderer, part, x, y, color, false);
            y += 12;
            rest = rest.substring(part.length());
        }
        return y;
    }

    private int drawWrapped(DrawContext context, String line, int x, int y, int maxWidth, int color) {
        if (line == null || line.isEmpty()) {
            return y + 12;
        }
        String rest = line;
        while (!rest.isEmpty() && y < this.height) {
            String part = rest;
            while (textRenderer.getWidth(part) > maxWidth && part.length() > 1) {
                int split = part.lastIndexOf(' ');
                if (split <= 0) {
                    break;
                }
                part = part.substring(0, split);
            }
            context.drawText(textRenderer, part, x, y, color, false);
            y += 12;
            if (part.equals(rest)) {
                break;
            }
            rest = rest.substring(part.length()).stripLeading();
        }
        return y;
    }

    private int statusColor(String status) {
        if (status.contains("无法") || status.contains("暂停")) {
            return 0xFFFF7777;
        }
        if (status.contains("不足") || status.contains("紧张")) {
            return 0xFFE8D090;
        }
        return 0xFF77DD77;
    }

    private static Page pageFor(String label) {
        return switch (label) {
            case "存入" -> Page.DEPOSIT;
            case "取出" -> Page.WITHDRAW;
            case "硬通货兑换" -> Page.EXCHANGE;
            case "开立汇票" -> Page.ISSUE;
            case "兑付汇票" -> Page.REDEEM;
            case "个人银行券交易" -> Page.MARKET;
            default -> Page.SAVINGS;
        };
    }

    private int controlsTop() {
        return BankLayout.contentBottom(this.height) - 64;
    }

    private int listFloor() {
        return controlsTop() - 16;
    }

    private int fittedTop(int desired) {
        int floor = listFloor();
        if (desired + 18 <= floor) {
            return desired;
        }
        return Math.max(contentTop(), floor - 18);
    }

    private int fittedHeight(int top) {
        return Math.max(18, listFloor() - top);
    }

    private int[] splitBand(int start, int preferredFirst) {
        int top = fittedTop(start);
        int floor = listFloor();
        int available = Math.max(40, floor - top);
        int first = Math.min(preferredFirst, Math.max(28, available / 2));
        int secondTop = top + first + 4;
        int second = floor - secondTop;
        if (second < 18) {
            second = 18;
            secondTop = Math.max(top + 22, floor - second);
            first = Math.max(18, secondTop - 4 - top);
        }
        return new int[] {top, first, secondTop, second};
    }

    private List<ChoiceListWidget.Choice> exchangeChoices() {
        List<ChoiceListWidget.Choice> choices = new ArrayList<>(moneyChoices(BankSnapshot.hardCurrencies(data)));
        for (BankSnapshot.NoteRow note : BankSnapshot.notes(data)) {
            String label = note.name();
            for (BankSnapshot.MoneyRow money : BankSnapshot.currencies(data)) {
                if (money.name().equals(note.name())) {
                    label = "个人银行券 " + note.name();
                    break;
                }
            }
            choices.add(new ChoiceListWidget.Choice(BankExchange.idForNote(note.name()), label));
        }
        return choices;
    }

    private void ensureExchangeIds(List<ChoiceListWidget.Choice> rows) {
        if (!containsChoice(rows, payId) && !rows.isEmpty()) {
            payId = rows.get(0).id();
        }
        if (!containsChoice(rows, receiveId) || receiveId.equals(payId)) {
            for (ChoiceListWidget.Choice choice : rows) {
                if (!choice.id().equals(payId)) {
                    receiveId = choice.id();
                    return;
                }
            }
        }
    }

    private static boolean containsChoice(List<ChoiceListWidget.Choice> rows, String id) {
        for (ChoiceListWidget.Choice choice : rows) {
            if (choice.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "—" : value;
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
