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
        SAVINGS, DEPOSIT, WITHDRAW, EXCHANGE, ISSUE, REDEEM, MARKET, ISSUANCE, ISSUANCE_SWITCH, ISSUANCE_RESERVE, ISSUANCE_RENAME,
        MANAGEMENT, MANAGEMENT_ADJUST, CONFIRM
    }

    private record Placed(String role, ChoiceListWidget widget, int x, int y, int width, int height, boolean inScroll) {
    }

    private JsonObject data;
    private Page page = Page.SAVINGS;
    private String payId = "";
    private String receiveId = "";
    private String exchangeMetal = "diamond";
    private boolean exchangeSelling = true;
    private String noteName = "";
    private String otherNote = "";
    private String marketMode = "buy";
    private boolean payeeSelf;
    private String amountText = "1";
    private final AmountDrafts amountDrafts = new AmountDrafts();
    private String notice = "";
    private String openDrawer = "";
    private String switchRegime = "";
    private String switchAnchor = "";
    private String displayedPolicy;
    private String pendingPolicyRegime = "";
    private String policyLower;
    private String policyUpper;
    private String quantityDirection = "increase";
    private String issuanceQuantity = "";
    private String renameText = "";
    private String createName = "";
    private String createRegime = "float";
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
        amountText = amountDrafts.select(page.name());
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
            case SAVINGS -> initSavingsList();
            case DEPOSIT -> initMoney(BankSnapshot.currencies(data), true, "确认存入");
            case WITHDRAW -> initMoney(BankSnapshot.currencies(data), true, "确认取出");
            case EXCHANGE -> initExchange();
            case ISSUE -> initIssue();
            case REDEEM -> initRedeem();
            case MARKET -> initMarket();
            case ISSUANCE -> initIssuance();
            case ISSUANCE_SWITCH -> initIssuanceSwitch();
            case ISSUANCE_RESERVE -> initReserveSettings();
            case ISSUANCE_RENAME -> initRenameSettings();
            default -> {
            }
        }
    }

    private void addChrome() {
        addTopButtons();
        addRow(page == Page.SAVINGS && data.has("welcomeGift")
                ? List.of("储蓄", "存入", "取出", "新人礼包") : BankChrome.ROW_ONE, BankLayout.row1Y(this.height));
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
        int right = this.width - BankLayout.EDGE;
        if (y == BankLayout.closeY(this.height)) {
            right -= buttonWidth(BankChrome.CLOSE) + BankLayout.GAP;
        }
        int maximumWidth = Math.max(20, (right - x - BankLayout.GAP * (labels.size() - 1)) / labels.size());
        for (String label : labels) {
            int width = Math.min(buttonWidth(label), maximumWidth);
            addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> { if ("新人礼包".equals(label)) client.setScreen(new WelcomeGiftScreen(data, this)); else open(pageFor(label)); })
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
        BankLayout.Box port = issuancePort();
        IssuancePane pane = new IssuancePane(port.x(), port.y(), port.width(), port.height(), this::toggleIssuanceDrawer, this::pickIssuanceDrawer);
        issuancePane = pane;
        int left = port.x() + 4, right = port.right() - 8, y = port.y() + 4;
        y = paneButtons(pane, left, right, y, List.of(exchangeMetal.equals("diamond") ? "✓ 钻石" : "钻石", exchangeMetal.equals("copper") ? "✓ 铜锭" : "铜锭"), List.of(
                () -> chooseHardExchange("diamond", exchangeSelling), () -> chooseHardExchange("copper", exchangeSelling)));
        y = paneButtons(pane, left, right, y + 6, List.of("实物换央行券", "央行券换实物"), List.of(
                () -> chooseHardExchange(exchangeMetal, true), () -> chooseHardExchange(exchangeMetal, false)));
        String metalName = currencyName(exchangeMetal);
        y = paneText(pane, (exchangeSelling ? "付出" : "得到") + "实物数量（整数 " + metalName + "）", left, y + 8, right - left, 0xFFE8D090);
        y = addIssuanceAmount(pane, left, right, right - left, y + 6);
        String pay = exchangeSelling ? exchangeMetal : "note", receive = exchangeSelling ? "note" : exchangeMetal;
        y = paneButtons(pane, left, right, y, List.of("预览兑换"), List.of(() -> {
            rememberAmount();
            if (!amountText.matches("[0-9]{1,9}") || new java.math.BigInteger(amountText).signum() <= 0) {
                showNotice("请输入正整数实物数量。"); return;
            }
            send("hard-exchange-quote " + pay + " " + receive + " " + amountText);
        }));
        String message = BankSnapshot.text(data, "exchangeMessage");
        if (message != null && !message.isBlank()) y = paneText(pane, message, left, y + 8, right - left, 0xFFE8D090);
        String token = BankSnapshot.text(data, "exchangeToken");
        if (token != null && !token.isBlank() && pay.equals(BankSnapshot.text(data, "exchangePay"))
                && receive.equals(BankSnapshot.text(data, "exchangeReceive"))) {
            y = paneButtons(pane, left, right, y + 6, List.of("确认以上报价"), List.of(() -> {
                rememberAmount();
                if (!amountText.matches("[0-9]{1,9}") || !new java.math.BigInteger(amountText).toString().equals(BankSnapshot.text(data, "exchangeQuantity"))) {
                    showNotice("数量已修改，请重新预览。"); return;
                }
                data.remove("exchangeToken");
                send("hard-exchange-confirm " + token);
            }));
        }
        y = paneText(pane, "兑换入个人储蓄，需要物品时再取出。央行券可有小数；实物只整件交易。零散账面权益保留，不会被舍弃。每次最多 4096 件。", left, y + 8, right - left, 0xFFAAAAAA);
        if (!notice.isBlank()) y = paneText(pane, notice, left, y + 6, right - left, 0xFFFFCC88);
        pane.finish(y);
        pane.setScroll(issuanceScroll);
    }

    private void chooseHardExchange(String metal, boolean selling) {
        rememberAmount();
        exchangeMetal = metal;
        exchangeSelling = selling;
        data.remove("exchangeToken");
        data.remove("exchangeMessage");
        issuanceScroll = 0;
        clearAndInit();
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

    private void openPersonalMarket() {
        try {
            Object opened = Class.forName("dev.jdguo.market.ui.MarketUiClient")
                    .getMethod("requestPersonalMarket").invoke(null);
            if (!Boolean.TRUE.equals(opened)) {
                notice = "市场连接尚未就绪，请稍后重试。";
                if (client != null) client.execute(this::clearAndInit);
            }
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            notice = "请安装市场模组 1.0.6 或更新版本以打开个人币交易与兑换界面。";
            if (client != null) client.execute(this::clearAndInit);
        }
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
        BankLayout.MarketStack stack = compactMarket()
                ? BankLayout.compactMarketStack(headerBottom, BankLayout.contentBottom(this.height),
                        !external && "mode".equals(openDrawer), 4, secondOpen, Math.max(1, second.size()))
                : BankLayout.marketStack(headerBottom, BankLayout.contentBottom(this.height),
                        !external && "mode".equals(openDrawer), 4, secondOpen, Math.max(1, second.size()));
        pageBand = new BankLayout.Band(stack.firstY(), stack.firstH(), stack.fieldY(), stack.quickY(), stack.actionY());
        marketBalanceY = stack.secondY() + stack.secondH() + 4;
        if (external) {
            int[] columns = twoColumns();
            addDrawableChild(textButton("市场买入/挂牌", columns[0], stack.firstY(), this::openPersonalMarket));
            ButtonWidget transfer = addDrawableChild(textButton("转给玩家", columns[1], stack.firstY(),
                    () -> openPersonalCommand("/bank note transfer " + (noteName.isBlank() ? "" : "\"" + noteName + "\" "))));
            transfer.active = !notes.isEmpty();
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
            // Redemption follows actual collateral to its terminal asset, never a selected appraisal currency.
            receiveId = "note"; // legacy wire argument; the server resolves the actual terminal currency.
            placeDrawer("note", centerX(listWidth()), stack.secondY(), listWidth(), second, noteName, stack.secondH());
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
                    new ChoiceListWidget.Choice("hard", "固定汇率制")), createRegime);
            y += 6;
            y = policyField(pane, "货币名称（最多三个汉字或英文字母）", createName, left, right, y,
                    value -> createName = value);
            y = paneButtons(pane, left, right, y + 4, List.of("确认创建个人币"), List.of(() -> {
                if (!createName.matches("[\\p{IsHan}a-zA-Z]{1,3}")) {
                    showNotice("请输入一至三个汉字或英文字母作为货币名称。");
                    return;
                }
                send("note-create " + createRegime + " diamond " + createName + " 1");
            }));
            y = paneText(pane, "先创建名称和制度，再存入准备金及发行。创建本身不会新增流通货币。",
                    left, y + 6, textWidth, 0xFFE6E6E6);
            if (!notice.isBlank()) y = paneText(pane, notice, left, y, textWidth, 0xFFFFCC88);
            for (String rule : List.of(
                    "浮动：实际准备金支持价随储备与有效流通变化，可手动增发。",
                    "联系：只设区间上下限，以对应储蓄及发行人可用币调节。",
                    "固定：按固定制度管理；准备金不足时，以真实支持为准。")) {
                y = paneText(pane, rule, left, y, textWidth, 0xFFAAAAAA);
            }
            pane.finish(y);
            pane.setScroll(issuanceScroll);
            return;
        }
        JsonObject own = BankSnapshot.ownNote(data);
        String regimeName = BankSnapshot.regimeName(data);
        String active = BankActions.regimeToken(BankSnapshot.text(own, "regime"));
        if (!active.equals(displayedPolicy)) {
            displayedPolicy = active; pendingPolicyRegime = "";
            policyLower = BankSnapshot.text(own, "pegLower");
            policyUpper = BankSnapshot.text(own, "pegUpper");
        }
        if (!notice.isBlank()) y = paneText(pane, notice, left, y, textWidth, 0xFFFFCC88) + 4;
        Boolean paused = BankSnapshot.notePaused(data);
        String modeStatus = Boolean.TRUE.equals(paused) ? "已暂停" : paused == null ? BankBook.MISSING : "正常";
        String anchor = BankSnapshot.text(own, "anchor");
        String anchorDisplay = anchor == null ? BankBook.MISSING : anchorName(anchor);
        boolean autoSale = "true".equals(BankSnapshot.text(own, "autoListing"));
        boolean settlementPending = "true".equals(BankSnapshot.text(own, "pendingSettlement"));
        boolean reserveChanging = "true".equals(BankSnapshot.text(own, "reserveChanging"));
        boolean integrated = "true".equals(BankSnapshot.text(own, "integratedPolicyUi"));
        if (reserveChanging) modeStatus = "准备金更换中";
        if (BankSnapshot.bankrupt(data)) modeStatus = "已破产";
        y = paneRow(pane, "发行制度 / 兑付", safe(regimeName) + " / " + modeStatus, left, right, y, true);
        y = paneRow(pane, "锚定资产", anchorDisplay, left, right, y, false);
        y = paneRow(pane, "实际支持价 / 币", safe(BankSnapshot.text(own, "supportRate")) + (anchor == null ? "" : " " + anchorDisplay), left, right, y, false);
        y = paneRow(pane, "账户项目", "余额 / 数量", left, right, y + 6, true);
        String issuanceHolding = BankSnapshot.text(own, "holdingExact");
        if (issuanceHolding == null) issuanceHolding = BankSnapshot.text(own, "holding");
        String reserveUnit = BankSnapshot.text(own, "reserveCurrency");
        y = paneRow(pane, "发行人持有", safe(issuanceHolding), left, right, y, false);
        y = paneRow(pane, "实际准备金", safe(BankSnapshot.text(own, "reserveAmount")) +
                (reserveUnit == null ? "" : " " + anchorName(reserveUnit)), left, right, y, false);
        y = paneRow(pane, "可动用准备金", safe(BankSnapshot.text(own, "issuanceFree")), left, right, y, false);
        y = paneRow(pane, "回收库（不流通）", safe(BankSnapshot.text(own, "recovered")), left, right, y, false);
        y = paneRow(pane, autoSale ? "自动待售" : "手动挂牌", safe(BankSnapshot.text(own, "offered")), left, right, y, false);
        for (String metric : List.of("m0", "m1", "m2"))
            y = paneRow(pane, metric.toUpperCase(java.util.Locale.ROOT), safe(BankSnapshot.text(own, metric)), left, right, y, false);
        String m2Basis = BankSnapshot.text(own, "m2Basis");
        if (m2Basis != null) y = paneText(pane, m2Basis, left, y + 4, textWidth, 0xFFAAAAAA);
        if (settlementPending) y = paneText(pane, "有待交付交易，相关准备金和回收币暂锁。", left, y + 4, textWidth, 0xFFFFCC88);
        if (BankSnapshot.bankrupt(data)) {
            pane.finish(y); pane.setScroll(issuanceScroll); return;
        }
        y = appendActivePolicy(pane, own, active, anchorDisplay, integrated, settlementPending || reserveChanging, left, right, y + 8);
        y = appendQuantityAdjustment(pane, active, anchorDisplay, settlementPending || reserveChanging, left, right, y + 8);
        y = paneText(pane, "准备金与回流操作", left, y + 10, textWidth, 0xFFE8D090);
        y = addIssuanceAmount(pane, left, right, textWidth, y + 4);
        String freezeLabel = Boolean.TRUE.equals(paused) ? "恢复兑付" : "暂停兑付";
        y = paneButtons(pane, left, right, y, List.of(freezeLabel), List.of(
                () -> send(Boolean.TRUE.equals(paused) ? BankActions.noteUnfreeze() : BankActions.noteFreeze())));
        y = paneButtons(pane, left, right, y + 4, List.of("存入准备金", "取出准备金"), List.of(
                () -> sendAmount(BankActions::reserveDeposit), () -> sendAmount(BankActions::reserveWithdraw)));
        if (clearingEnabled()) {
            y = paneButtons(pane, left, right, y + 4, List.of("回流所填数量", "更换准备金"), List.of(
                    () -> sendAmount(value -> "note-recovery-return " + value), () -> open(Page.ISSUANCE_RESERVE)));
            y = paneText(pane, "float".equals(active) ? "回流不自动补充准备金，会改变实际支持价。"
                    : "回流按本制度尝试从发行人对应储蓄补准备金，不扣公众余额。", left, y + 4, textWidth, 0xFFAAAAAA);
        }
        AutoSaleSwitch saleSwitch = new AutoSaleSwitch(left, y + 6, right - left, autoSale, () -> {
            String id = BankSnapshot.text(own, "id");
            if (id != null) send("note-auto-listing " + id + (autoSale ? " off" : " on"));
        });
        saleSwitch.active = "true".equals(BankSnapshot.text(own, "autoListingAvailable")) && !settlementPending;
        pane.addControl(saleSwitch, left, y + 6);
        y = paneText(pane, "自动待售仅使用发行人可售余额；关闭不删除市场条目。", left, y + 33, textWidth, 0xFFAAAAAA);
        if ("true".equals(BankSnapshot.text(own, "canRename"))) {
            y = paneButtons(pane, left, right, y + 6, List.of("修改货币名称"), List.of(() -> open(Page.ISSUANCE_RENAME)));
        }
        y = paneButtons(pane, left, right, y + 6, List.of("宣告破产"), List.of(() -> {
            send(BankActions.noteBankrupt()); page = Page.CONFIRM; client.execute(this::clearAndInit);
        }));
        y = appendSettlementHistory(pane, own, left, right, y);
        pane.finish(y);
        pane.setScroll(issuanceScroll);
    }

    private int appendQuantityAdjustment(IssuancePane pane, String active, String anchor, boolean blocked, int left, int right, int y) {
        int w = right - left;
        y = paneRow(pane, "货币数量调整", "个人币 · 整数", left, right, y, true);
        if (!"true".equals(BankSnapshot.text(data, "personalQuantitySupported")))
            return paneText(pane, "升级服务端后可在这里预览增发和收回。", left, y + 4, w, 0xFFFFCC88);
        if (blocked)
            return paneText(pane, "待结算或更换准备金期间暂不能增发或收回。", left, y + 4, w, 0xFFFFCC88);
        boolean increase = "increase".equals(quantityDirection);
        y = paneButtons(pane, left, right, y + 5, List.of(increase ? "▶ 增发" : "增发", increase ? "减少发行量" : "▶ 减少发行量"), List.of(
                () -> changeQuantityDirection(pane, "increase"), () -> changeQuantityDirection(pane, "decrease")));
        String hint = increase ? ("float".equals(active) ? "增发到发行人账户，不自动追加准备金。"
                : "按当前制度及现有准备金校验，不够则不发行。")
                : "注销发行人可用的币，不进回收库；不扣其他玩家，也不动已锁定的挂牌。";
        y = paneText(pane, hint, left, y + 4, w, 0xFFAAAAAA);
        JsonObject preview = data.has("personalQuantityPreview") && data.get("personalQuantityPreview").isJsonObject()
                ? data.getAsJsonObject("personalQuantityPreview") : null;
        boolean matching = preview != null && active.equals(BankSnapshot.text(preview, "regime"))
                && quantityDirection.equals(BankSnapshot.text(preview, "direction"))
                && IssuancePolicyForm.samePositiveWhole(issuanceQuantity, BankSnapshot.text(preview, "amount"));
        String token = matching ? BankSnapshot.text(preview, "token") : null;
        int fieldWidth = Math.min(120, Math.max(20, w - 90));
        TextFieldWidget quantity = new TextFieldWidget(textRenderer, left, y + 5, fieldWidth, 20, Text.literal("个人币数量"));
        quantity.setMaxLength(24);
        quantity.setTextPredicate(value -> value.matches("[0-9]{0,24}"));
        quantity.setText(issuanceQuantity);
        pane.addControl(quantity, left, y + 5);
        ButtonWidget prepare = ButtonWidget.builder(Text.literal("预览变动"), button -> {
            try {
                String request = IssuancePolicyForm.quantityPreview(quantityDirection, issuanceQuantity);
                issuanceScroll = pane.scroll(); send(request);
            } catch (IllegalArgumentException invalid) { showNotice(invalid.getMessage()); }
        }).dimensions(left + fieldWidth + 5, y + 5, w - fieldWidth - 5, 20).build();
        prepare.active = IssuancePolicyForm.samePositiveWhole(issuanceQuantity, issuanceQuantity);
        pane.addControl(prepare, left + fieldWidth + 5, y + 5);
        y += 29;
        ButtonWidget confirm = null;
        if (matching && !"true".equals(BankSnapshot.text(preview, "allowed"))) {
            y = paneText(pane, safe(BankSnapshot.text(preview, "reason")), left, y + 4, w, 0xFFFFCC88);
        } else if (matching && token != null && !token.isBlank()) {
            y = paneRow(pane, "发行人持有", quantityChange(preview, "holding"), left, right, y, false);
            if (!increase) y = paneRow(pane, "发行人可用", quantityChange(preview, "available"), left, right, y, false);
            y = paneRow(pane, "发行总量", quantityChange(preview, "issued"), left, right, y, false);
            y = paneRow(pane, "有效流通量", quantityChange(preview, "active"), left, right, y, false);
            y = paneRow(pane, "实际准备金", quantityChange(preview, "reserve") + " " + anchor, left, right, y, false);
            if (!increase || !"float".equals(active))
                y = paneRow(pane, "锁定准备金", quantityChange(preview, "locked"), left, right, y, false);
            y = paneText(pane, "预览有效 60 秒；改数量、切换方向或账本变化后要重新预览。", left, y + 4, w, 0xFFAAAAAA);
            int half = (w - 4) / 2, buttonY = y + 4;
            var cancel = ButtonWidget.builder(Text.literal("取消预览"), button -> {
                cancelQuantityPreview(); issuanceScroll = pane.scroll(); clearAndInit();
            }).dimensions(left, buttonY, half, 18).build();
            pane.addControl(cancel, left, buttonY);
            confirm = ButtonWidget.builder(Text.literal(increase ? "确认增发" : "确认减少"), button -> {
                button.active = false; issuanceScroll = pane.scroll(); send("note-quantity-confirm " + token);
            }).dimensions(left + half + 4, buttonY, w - half - 4, 18).build();
            pane.addControl(confirm, left + half + 4, buttonY);
            y = buttonY + 18;
        }
        ButtonWidget confirmation = confirm;
        quantity.setChangedListener(value -> {
            issuanceQuantity = value;
            prepare.active = IssuancePolicyForm.samePositiveWhole(value, value);
            if (confirmation != null) confirmation.active = false;
            if (data.has("personalQuantityPreview")) cancelQuantityPreview();
        });
        return y;
    }

    private static String quantityChange(JsonObject preview, String field) {
        return safe(BankSnapshot.text(preview, field + "Before")) + " → " + safe(BankSnapshot.text(preview, field + "After"));
    }

    private void changeQuantityDirection(IssuancePane pane, String next) {
        if (quantityDirection.equals(next)) return;
        quantityDirection = next; issuanceQuantity = "";
        cancelQuantityPreview(); issuanceScroll = pane.scroll(); clearAndInit();
    }

    private void cancelQuantityPreview() {
        data.remove("personalQuantityPreview");
        if ("true".equals(BankSnapshot.text(data, "personalQuantitySupported"))) send("note-quantity-cancel");
    }

    private int paneRow(IssuancePane pane, String label, String value, int left, int right, int y, boolean header) {
        int split = left + (right - left) * 44 / 100;
        var keys = BankText.lines(label, Math.max(1, split - left - 8), textRenderer::getWidth);
        var values = BankText.lines(value, Math.max(1, right - split - 8), textRenderer::getWidth);
        int h = Math.max(1, Math.max(keys.size(), values.size())) * 11 + 5;
        pane.addBand(left, y, right, y + h - 1, header ? 0xFF342E23 : 0xB021242B);
        for (int i = 0; i < keys.size(); i++) pane.addLine(keys.get(i), left + 4, y + 3 + 11 * i, header ? 0xFFE8D090 : 0xFFE6E6E6);
        for (int i = 0; i < values.size(); i++) {
            String row = values.get(i);
            pane.addLine(row, right - 4 - textRenderer.getWidth(row), y + 3 + 11 * i, header ? 0xFFE8D090 : 0xFFB7D4B0);
        }
        return y + h;
    }

    private static String policyName(String mode) {
        return switch (mode) {
            case "peg" -> BankRegimes.PEG_NAME;
            case "hard" -> BankRegimes.HARD_NAME;
            default -> BankRegimes.FLOAT_NAME;
        };
    }

    private int appendActivePolicy(IssuancePane pane, JsonObject own, String active, String anchor, boolean integrated,
                                   boolean blocked, int left, int right, int y) {
        int w = right - left;
        String status = BankSnapshot.text(own, "policyActive");
        String label = "true".equals(status) ? "已配置自动调节" : "未启用自动调节";
        y = paneRow(pane, "当前制度规则", "float".equals(active) ? "随实际准备金变动" : label, left, right, y, true);
        if ("peg".equals(active)) {
            int mid = left + (w - 6) / 2;
            String low = safe(BankSnapshot.text(own, "pegLower")), high = safe(BankSnapshot.text(own, "pegUpper"));
            if (integrated && !blocked) {
                if (policyLower == null) policyLower = BankSnapshot.text(own, "pegLower");
                if (policyUpper == null) policyUpper = BankSnapshot.text(own, "pegUpper");
                int leftEnd = policyField(pane, "区间下限", policyLower, left, mid, y + 4, value -> policyLower = value);
                int rightEnd = policyField(pane, "区间上限", policyUpper, mid + 6, right, y + 4, value -> policyUpper = value);
                y = Math.max(leftEnd, rightEnd);
                y = paneButtons(pane, left, right, y, List.of("保存区间"), List.of(() -> {
                    try { send(IssuancePolicyForm.bandCommand(active, policyLower, policyUpper)); }
                    catch (IllegalArgumentException invalid) { showNotice(invalid.getMessage()); }
                }));
            } else {
                int leftEnd = paneRow(pane, "下限", low, left, mid, y + 4, false);
                y = Math.max(leftEnd, paneRow(pane, "上限", high, mid + 6, right, y + 4, false));
            }
            y = paneText(pane, "每 1 个人币对应的 " + anchor + " 数量。低于下限尝试补准备金；高于上限按现行规则调节发行量。", left, y + 4, w, 0xFFAAAAAA);
        } else if ("hard".equals(active)) {
            y = paneRow(pane, "固定约定（只读）", safe(BankSnapshot.text(own, "policyTarget")), left, right, y, false);
            y = paneText(pane, "固定约定不等于可兑付余额。资金不足时，看上方实际支持价及兑付状态。", left, y + 4, w, 0xFFAAAAAA);
        } else {
            y = paneText(pane, "只随准备金和有效流通量重新计价，不设上下限、不自动干预。", left, y + 4, w, 0xFFAAAAAA);
        }
        if (!integrated || blocked) {
            return paneText(pane, integrated ? "待结算或更换准备金期间，不能调整制度。" : "更新服务端后可在本页调整制度。", left, y + 4, w, 0xFFFFCC88);
        }
        if (pendingPolicyRegime.isEmpty()) {
            List<String> options = new ArrayList<>();
            List<Runnable> changes = new ArrayList<>();
            for (String target : List.of("float", "peg", "hard")) if (!target.equals(active)) {
                options.add("切换到" + policyName(target).replace("汇率制", ""));
                changes.add(() -> { pendingPolicyRegime = target; issuanceScroll = pane.scroll(); clearAndInit(); });
            }
            y = paneButtons(pane, left, right, y + 5, options, changes);
        } else {
            y = paneText(pane, "准备切换为" + policyName(pendingPolicyRegime)
                    + "；锚定资产不变。是否能切换以服务端账本校验为准。", left, y + 4, w, 0xFFFFCC88);
            y = paneButtons(pane, left, right, y + 4, List.of("确认切换制度", "取消"), List.of(
                    () -> { String next = pendingPolicyRegime; pendingPolicyRegime = ""; cancelQuantityPreview(); send("note-policy-switch " + active + " " + next); clearAndInit(); },
                    () -> { pendingPolicyRegime = ""; issuanceScroll = pane.scroll(); clearAndInit(); }));
        }
        return y;
    }

    private int addIssuanceAmount(IssuancePane pane, int left, int right, int textWidth, int y) {
        int fieldWidth = Math.min(120, Math.max(32, textWidth - 52));
        int fieldX = left + Math.max(0, (textWidth - fieldWidth) / 2);
        amount = new TextFieldWidget(textRenderer, fieldX, y, fieldWidth, 18, Text.literal("数量"));
        amount.setMaxLength(32);
        if (page == Page.EXCHANGE) {
            // Set the predicate before the text: a stale non-numeric token must
            // never become an uneditable value in the integer-only field.
            amount.setTextPredicate(AmountDrafts::isPhysicalInput);
            amountText = AmountDrafts.physicalInput(amountText);
        }
        amount.setText(amountText);
        amountDrafts.remember(amountText);
        amount.setChangedListener(value -> {
            amountText = value;
            amountDrafts.remember(value);
        });
        pane.addControl(amount, fieldX, y);
        ButtonWidget minus = ButtonWidget.builder(Text.literal("−"), button -> adjustIssuanceAmount(-1))
                .dimensions(fieldX - 24, y, 20, 18).build();
        ButtonWidget plus = ButtonWidget.builder(Text.literal("+"), button -> adjustIssuanceAmount(1))
                .dimensions(fieldX + fieldWidth + 4, y, 20, 18).build();
        pane.addControl(minus, fieldX - 24, y);
        pane.addControl(plus, fieldX + fieldWidth + 4, y);
        return y + 26;
    }

    private void adjustIssuanceAmount(int step) {
        try {
            java.math.BigInteger value = new java.math.BigInteger(amount.getText().trim());
            quick(value.add(java.math.BigInteger.valueOf(step)).max(java.math.BigInteger.ONE).toString());
        } catch (NumberFormatException ignored) {
            showNotice("请输入整数数量后再调整。");
        }
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
        if (page == Page.EXCHANGE || page == Page.ISSUANCE_SWITCH || page == Page.ISSUANCE_RESERVE || page == Page.ISSUANCE_RENAME) {
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

    private void initRenameSettings() {
        BankLayout.Box port = issuancePort();
        IssuancePane pane = new IssuancePane(port.x(), port.y(), port.width(), port.height(), this::toggleIssuanceDrawer, this::pickIssuanceDrawer);
        issuancePane = pane;
        int left = port.x() + 4, right = port.right() - 8, y = port.y() + 4;
        y = paneButtons(pane, left, right, y, List.of("返回个人发行"), List.of(() -> open(Page.ISSUANCE)));
        y = paneText(pane, "一至三个汉字或英文字母；名称不可与其他货币重复。改名不会重建货币或改动余额。旧纸币仍可按原标识兑付。", left, y + 8, right - left, 0xFFE8D090);
        if (!notice.isBlank()) y = paneText(pane, notice, left, y + 4, right - left, 0xFFFFCC88);
        y = policyField(pane, "新名称", renameText, left, right, y + 8, value -> renameText = value);
        y = paneButtons(pane, left, right, y + 4, List.of("确认改名"), List.of(() -> {
            JsonObject own = BankSnapshot.ownNote(data);
            if (!"true".equals(BankSnapshot.text(own, "canRename"))) {
                showNotice("仅发行人或授权管理员可以改名。");
                return;
            }
            if (!dev.jdguo.bank.market.PersonalMarket.validName(renameText)) {
                showNotice("名称须为一至三个汉字或英文字母。");
                return;
            }
            String id = BankSnapshot.text(own, "id");
            if (id != null) send("note-rename " + id + " " + renameText);
        }));
        pane.finish(y);
        pane.setScroll(issuanceScroll);
    }

    private boolean clearingEnabled() {
        return "true".equals(BankSnapshot.text(BankSnapshot.ownNote(data), "clearingEnabled"));
    }

    private int appendSettlementHistory(IssuancePane pane, JsonObject own, int left, int right, int y) {
        y = paneText(pane, "最近系统结算（最多 5 笔）", left, y + 8, right - left, 0xFFE8D090);
        if (own == null || !own.has("settlements") || !own.get("settlements").isJsonArray()) {
            return paneText(pane, "服务端尚未提供持久化结算记录。", left, y, right - left, 0xFFAAAAAA);
        }
        int count = 0;
        for (var element : own.getAsJsonArray("settlements")) {
            if (!element.isJsonObject() || count >= 5) continue;
            JsonObject entry = element.getAsJsonObject();
            String summary = BankSnapshot.text(entry, "summary");
            if (summary == null) continue;
            String time = BankSnapshot.text(entry, "time");
            y = paneText(pane, (time == null ? "" : time + "  ") + summary, left, y + 4, right - left, 0xFFE6E6E6);
            count++;
        }
        if (count == 0) y = paneText(pane, "暂无已完成的系统结算。", left, y, right - left, 0xFFAAAAAA);
        return y;
    }

    private int policyField(IssuancePane pane, String label, String value, int left, int right, int y,
                            java.util.function.Consumer<String> update) {
        y = paneText(pane, label, left, y, right - left, 0xFFE6E6E6);
        TextFieldWidget field = new TextFieldWidget(textRenderer, left, y + 2,
                Math.max(20, Math.min(180, right - left)), 20, Text.literal(label));
        field.setMaxLength(25);
        if ("区间下限".equals(label) || "区间上限".equals(label))
            field.setTextPredicate(input -> input.matches("[0-9]{0,18}(\\.[0-9]{0,4})?"));
        field.setText(value == null ? "" : value);
        field.setChangedListener(update);
        pane.addControl(field, left, y + 2);
        return y + 28;
    }

    private void initReserveSettings() {
        BankLayout.Box port = issuancePort();
        IssuancePane pane = new IssuancePane(port.x(), port.y(), port.width(), port.height(), this::toggleIssuanceDrawer, this::pickIssuanceDrawer);
        issuancePane = pane;
        int left = port.x() + 4, right = port.right() - 8, y = port.y() + 4;
        y = paneButtons(pane, left, right, y, List.of("返回个人发行"), List.of(() -> open(Page.ISSUANCE)));
        JsonObject own = BankSnapshot.ownNote(data);
        boolean paused = "true".equals(BankSnapshot.text(own, "reserveChanging"));
        y = paneText(pane, paused ? "当前状态：更换中，计价与交易暂停。" : "当前状态：未更换。", left, y + 8, right - left, paused ? 0xFFFFCC88 : 0xFFE6E6E6);
        y = paneText(pane, "确认后剩余旧准备金转回个人储蓄。新准备金实际到账前，不允许报价或交易；流通币不会被扣除。", left, y + 4, right - left, 0xFFFFCC88);
        if (!notice.isBlank()) y = paneText(pane, notice, left, y + 4, right - left, 0xFFFFCC88);
        List<ChoiceListWidget.Choice> choices = new ArrayList<>();
        for (BankRegimes.AnchorRow row : BankRegimes.anchorRows(data)) {
            choices.add(new ChoiceListWidget.Choice(row.id(), row.label(), row.group(), row.allowed()));
        }
        y = placeScrollDrawer(pane, "switchAnchor", left, right, y + 8, choices, switchAnchor);
        for (BankRegimes.AnchorRow row : BankRegimes.anchorRows(data)) {
            if (!row.group() && !row.allowed()) {
                y = paneText(pane, row.label() + "：不可选择，" + row.reason(), left, y + 4, right - left, 0xFFFFCC88);
            }
        }
        y = paneButtons(pane, left, right, y + 8, List.of("确认返还并更换"), List.of(() -> {
            String anchor = selected("switchAnchor", switchAnchor);
            if (!clearingEnabled() || anchor.isBlank() || anchor.startsWith("group:")) {
                showNotice("请选择服务端支持的准备金币种。");
                return;
            }
            switchAnchor = anchor;
            send("note-reserve-switch " + anchor);
        }));
        y = addIssuanceAmount(pane, left, right, right - left, y + 12);
        y = paneButtons(pane, left, right, y, List.of("存入新准备金"), List.of(() -> sendAmount(BankActions::reserveDeposit)));
        pane.finish(y);
        pane.setScroll(issuanceScroll);
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
        if (clearingEnabled()) { open(Page.ISSUANCE); return; }
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
        y += 8;
        rememberIssuanceChoice();
        List<BankRegimes.AnchorRow> anchors = BankRegimes.anchorRows(data);
        y = placeScrollDrawer(pane, "switchRegime", left, right, y, List.of(
                new ChoiceListWidget.Choice("float", "浮动汇率制"),
                new ChoiceListWidget.Choice("peg", "联系汇率制"),
                new ChoiceListWidget.Choice("hard", "固定汇率制")), switchRegime);
        y += 6;
        List<ChoiceListWidget.Choice> anchorChoices = new ArrayList<>();
        for (BankRegimes.AnchorRow row : anchors) {
            anchorChoices.add(new ChoiceListWidget.Choice(row.id(), row.label(), row.group(), row.allowed()));
        }
        y = placeScrollDrawer(pane, "switchAnchor", left, right, y, anchorChoices, switchAnchor);
        y += 8;
        y = paneButtons(pane, left, right, y, List.of("确认切换制度"), List.of(this::confirmSwitch));
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
        amount.setText(amountText);
        amount.setChangedListener(value -> {
            amountText = value;
            amountDrafts.remember(value);
        });
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
        boolean inlineConfirm = page == Page.MARKET && compactMarket();
        int confirmWidth = verb == null ? 0 : buttonWidth(verb);
        int groupWidth = total + (inlineConfirm ? confirmWidth + 8 : 0);
        int x = mainLeft() + Math.max(0, (mainWidth() - groupWidth) / 2);
        for (int i = 0; i < labels.size(); i++) {
            String shown = labels.get(i);
            addDrawableChild(ButtonWidget.builder(Text.literal(shown), button -> quick(shown))
                    .dimensions(x, quickY, widths[i], 18).build());
            x += widths[i] + BankLayout.GAP;
        }
        if (verb != null) {
            int width = buttonWidth(verb);
            ButtonWidget confirm = addDrawableChild(ButtonWidget.builder(Text.literal(verb), button -> submit())
                    .dimensions(inlineConfirm ? x + 4 : centerX(width), actionY, width, 18).build());
            if (page == Page.MARKET) confirm.active = findNote(noteName) != null;
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
        if (page == Page.MARKET && mouseX >= mainLeft() && mouseX < mainRight()
                && mouseY >= contentTop() + 12 && mouseY < contentTop() + 24) {
            context.drawTooltip(textRenderer, Text.literal(notice.isBlank() ? marketHeading() : notice), mouseX, mouseY);
        }
        if (page == Page.MARKET) {
            for (Placed item : placed) {
                if (!showsBalance(item.role) || mouseX < item.x || mouseX >= item.x + item.width
                        || mouseY < marketBalanceY || mouseY >= marketBalanceY + 24) continue;
                String details = balanceCaption(item) + BankSnapshot.balancePhrase(balanceFor(item.role, item.widget.selectedId()));
                if ("note".equals(item.role)) {
                    details += "；" + BankRates.selectedPersonalQuote(data, findNote(item.widget.selectedId()));
                }
                context.drawTooltip(textRenderer, Text.literal(details), mouseX, mouseY);
                break;
            }
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
            if (line.startsWith("1 钻石") || line.startsWith("1 " + BankRates.centralName(data)) || line.startsWith("1 【")) {
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
            case EXCHANGE -> drawHeading(context, "实物货币兑换", "选择实物和方向 → 输入整件数量 → 预览并确认。");
            case ISSUE -> drawHeading(context, "开立汇票", "选择币种和抬头，再填写开出的数量。");
            case REDEEM -> drawRedeem(context);
            case MARKET -> drawMarketHeading(context);
            case ISSUANCE -> drawIssuanceTitle(context);
            case ISSUANCE_SWITCH -> drawSwitchTitle(context);
            case ISSUANCE_RESERVE -> drawHeading(context, "更换准备金", "返还旧准备金后暂停计价与交易，新准备金到账后恢复。");
            case ISSUANCE_RENAME -> drawHeading(context, "个人货币改名", "仅修改名称，余额、抵押链和纸币仍使用原有货币标识。");
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
        if (!notice.isBlank() && page != Page.CONFIRM && page != Page.ISSUANCE && page != Page.ISSUANCE_SWITCH && page != Page.ISSUANCE_RESERVE && page != Page.ISSUANCE_RENAME) {
            if (page != Page.MARKET) {
                context.drawText(textRenderer, notice, mainLeft(), contentTop(), 0xFFFFCC88, false);
            }
            // Market feedback occupies the help line instead of covering the first selector.
        }
    }

    private void drawSavings(DrawContext context) {
        drawCentered(context, BankChrome.SAVINGS_TITLE, contentTop(), 0xFFD0B060);
    }

    private void initSavingsList() {
        BankLayout.Box port = issuancePort();
        IssuancePane pane = new IssuancePane(port.x(), port.y(), port.width(), port.height(), this::toggleIssuanceDrawer, this::pickIssuanceDrawer);
        issuancePane = pane;
        int left = port.x() + 4, right = port.right() - 8, y = port.y() + 4;
        y = paneRow(pane, "个人存款", "余额", left, right, y, true);
        List<BankSnapshot.MoneyRow> rows = BankSnapshot.currencies(data);
        if (rows.isEmpty()) y = paneText(pane, "柜台还没有发来个人存款。", left, y + 4, right - left, 0xFFAAAAAA);
        for (BankSnapshot.MoneyRow row : rows) {
            String amount = ("diamond".equals(row.id()) || "copper".equals(row.id()))
                    ? PhysicalSavings.describe(row.amount()) : safe(row.amount());
            y = paneRow(pane, row.name(), amount, left, right, y, false);
        }
        y = paneRow(pane, "持有的个人币", "账面余额", left, right, y + 8, true);
        List<BankSnapshot.NoteRow> notes = BankSnapshot.heldNotes(data);
        if (notes.isEmpty()) y = paneText(pane, "没有持有个人币。", left, y + 4, right - left, 0xFFAAAAAA);
        for (BankSnapshot.NoteRow row : notes) y = paneRow(pane, row.name(), safe(row.balance()), left, right, y, false);
        if (!notes.isEmpty()) y = paneText(pane, "兑换牌价请在「个人币交易」查看。", left, y + 5, right - left, 0xFFAAAAAA);
        pane.finish(y); pane.setScroll(issuanceScroll);
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

    private boolean compactMarket() {
        return mainWidth() >= 240 && this.height < 360;
    }

    /** A fixed help/feedback line cannot push selectors down after a failed submission. */
    private int marketHeaderBottom() {
        return contentTop() + 24;
    }

    private void drawMarketHeading(DrawContext context) {
        int x = mainLeft();
        int y = contentTop();
        context.drawText(textRenderer, "个人币交易与兑换", x, y, 0xFFD0B060, false);
        if (amount != null && pageBand != null) {
            context.drawText(textRenderer, "数量", Math.max(mainLeft(), amount.getX() - textRenderer.getWidth("数量") - 6),
                    pageBand.fieldY() + 4, 0xFFAAAAAA, false);
        }
        String help = notice.isBlank() ? marketHeading() : notice;
        String fitted = textRenderer.trimToWidth(help, Math.max(40, mainWidth() - 12));
        if (!fitted.equals(help)) fitted += "…";
        context.drawText(textRenderer, fitted, x, y + 12, notice.isBlank() ? 0xFFAAAAAA : 0xFFFFCC88, false);
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
        context.drawText(textRenderer, page == Page.MARKET ? textRenderer.trimToWidth(line, item.width) : line,
                item.x, textY, 0xFFB7C7B0, false);
        if ("note".equals(item.role)) {
            BankSnapshot.NoteRow note = findNote(item.widget.selectedId());
            String quote = BankRates.selectedPersonalQuote(data, note);
            context.drawText(textRenderer, page == Page.MARKET ? textRenderer.trimToWidth(quote, item.width) : quote,
                    item.x, textY + 12, note == null || note.price() == null ? 0xFFAAAAAA : 0xFFE8D090, false);
        }
    }

    private String centralName() {
        String name = BankSnapshot.text(data, "centralCurrencyName");
        return name == null || name.isBlank() ? "央行券" : name;
    }

    private String anchorName(String anchor) {
        String token = BankActions.anchorToken(anchor);
        if (token == null) {
            return anchor == null ? BankBook.MISSING : anchor;
        }
        return switch (token) {
            case "diamond" -> "钻石";
            case "copper" -> "铜锭";
            default -> centralName();
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (issuancePane != null && issuancePane.contains(mouseX, mouseY)) {
            issuancePane.mouseClicked(mouseX, mouseY, button);

            return true;
        }
        if (issuancePane != null) issuancePane.clearFocus();
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
        if (issuancePane != null && issuancePane.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (issuancePane != null && issuancePane.charTyped(chr, modifiers)) return true;
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
                        receiveId = currencyId(BankSnapshot.hardCurrencies(data), "央行券", "note");
                    }
                }
            }
            case "payee" -> payeeSelf = "self".equals(id);
            case "regime" -> { createRegime = id; openDrawer = ""; client.execute(this::clearAndInit); }
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
            if (page == Page.ISSUANCE) cancelQuantityPreview();
            openDrawer = "";
            pendingPolicyRegime = "";
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
        if (note.isBlank() || findNote(note) == null) {
            notice = "请选择一张个人币。";
            return;
        }
        switch (marketMode) {
            case "sell" -> send(BankActions.noteSell(note, quantity));
            case "swap" -> {
                String other = selected("other", otherNote);
                if (other.isBlank() || other.equals(note)) {
                    notice = "请再选择一张要换成的个人币。";
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
            amountDrafts.remember(amountText);
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
            case "note" -> "个人币  ";
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
            return "挂牌买卖请前往市场；此处办理转账、通兑。";
        }
        return switch (marketMode) {
            case "sell" -> "卖出个人币，只换成央行券，不动发行人的准备金。";
            case "swap" -> "把一张个人币换成另一张，不动发行人的准备金。";
            case "redeem" -> "按实际抵押链兑付到个人储蓄；实物只按整数提取。";
            default -> "买入个人币，只用央行券支付，不动发行人的准备金。";
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
        for (String part : BankText.lines(line, Math.max(1, maxWidth), textRenderer::getWidth)) {
            if (y >= this.height) break;
            context.drawText(textRenderer, part, x, y, color, false);
            y += 12;
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
            case "实物货币兑换" -> Page.EXCHANGE;
            case "开立汇票" -> Page.ISSUE;
            case "兑付汇票" -> Page.REDEEM;
            case "个人币交易与兑换" -> Page.MARKET;
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
                    label = "个人币 " + note.name();
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
