package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;

/** Grouped operator workflow. No operation is hidden behind +/- controls. */
public final class BankAdminScreen extends Screen {
    private JsonObject data;
    private String selected = "note";
    private int page;
    private int scroll;
    private String typed = "1";
    private TextFieldWidget field;
    private String pending;
    private String renameDraft;
    private ButtonWidget confirmation;
    private boolean closing;
    private final List<String> lines = new ArrayList<>();
    private int textTop;

    public BankAdminScreen(JsonObject data) {
        super(Text.literal(BankChrome.MANAGEMENT));
        this.data = data == null ? new JsonObject() : data;
    }
    void show(JsonObject next) {
        remember();
        data = next == null ? new JsonObject() : next;
        clearAndInit();
    }
    private String centralName() {
        String name = BankSnapshot.text(data, "centralCurrencyName");
        return name == null || name.isBlank() ? "央行券" : name;
    }
    private String name() { return "note".equals(selected) ? centralName() : "copper".equals(selected) ? "铜锭" : "钻石"; }
    private int left() { return Math.max(10, (width - 420) / 2); }
    private int bodyWidth() { return Math.min(420, width - 20); }
    private void button(String label, int x, int y, int w, Runnable action) {
        ButtonWidget button = addDrawableChild(ButtonWidget.builder(Text.literal(textRenderer.trimToWidth(label, Math.max(1, w - 8))), b -> action.run()).dimensions(x, y, w, 20).build());
        if (label.startsWith("确认")) confirmation = button;
    }
    private void switchPage(int next) {
        remember(); page = next; scroll = 0; pending = null;
        BankUiClient.send("admin-policy-cancel");
        BankUiClient.send("admin-funding-cancel");
        clearAndInit();
    }
    @Override protected void init() {
        field = null; confirmation = null; lines.clear();
        int x = left(), w = bodyWidth();
        if (width < 250 || height < 230) {
            textTop = 42;
            lines.add("窗口过小，请降低 GUI 缩放或放大游戏窗口后管理央行。");
            button("关闭", x, height - 28, w, this::close);
            return;
        }
        button("更名设置", x, 6, 76, () -> switchPage(5));
        button("关闭", x + w - 50, 6, 50, this::close);
        String[] tabs = {"总览", "目标发行", "准备金", "回收", "兑付"};
        int tabW = (w - 16) / 5;
        for (int i = 0; i < tabs.length; i++) {
            final int tab = i;
            button((page == i ? "• " : "") + tabs[i], x + i * (tabW + 4), 32, tabW, () -> switchPage(tab));
        }
        textTop = 64;
        if (page == 0) {
            String[] ids = {"copper", "diamond", "note"};
            String[] names = {"铜锭", "钻石", centralName()};
            int cw = (w - 8) / 3;
            for (int i = 0; i < ids.length; i++) {
                final String id = ids[i];
                button((id.equals(selected) ? "▶ " : "") + names[i], x + i * (cw + 4), 60, cw,
                        () -> { selected = id; scroll = 0; clearAndInit(); });
            }
            textTop = 90;
            lines.add("央行货币供应");
            lines.addAll(BankBook.circulationLines(data));
            lines.add(""); lines.add(name() + "账户明细");
            lines.addAll(BankBook.book(data, selected, name()));
            lines.add("注意：存款保管包含玩家存款，不等于央行自有资产。");
            lines.add(BankRates.bankNoteQuote(data));
            button("商店储蓄管理", x, height - 30, (w - 4) / 2, () -> switchPage(8));
            button("地产储蓄管理", x + (w + 4) / 2, height - 30, (w - 4) / 2, () -> switchPage(9));
        } else if (page == 1) {
            input("目标汇率：钻石 / 1 " + centralName(), x, w);
            button("计算发行预览", x + w - 112, 86, 112, () -> {
                remember(); BankUiClient.send("admin-policy-preview " + typed);
            });
            textTop = 118;
            JsonObject quote = data.has("centralPolicyPreview") ? data.getAsJsonObject("centralPolicyPreview") : null;
            if (quote == null) {
                lines.add("输入目标汇率后预览，不默认按 1:1 发行。");
                lines.add("按实际准备金和现行汇率公式计算。仅使用已有准备金；不会从玩家保管款补足。");
            } else {
                lines.add(BankSnapshot.text(quote, "reason"));
                if (quote.get("allowed").getAsBoolean()) {
                    lines.add("本次增发：" + BankSnapshot.text(quote, "amount") + " " + centralName());
                    lines.add("现有准备金钻石等值：" + BankSnapshot.text(quote, "reserves"));
                    lines.add("发行后总额：" + BankSnapshot.text(quote, "liabilitiesAfter"));
                    lines.add("发行后 1 " + centralName() + " = " + BankSnapshot.text(quote, "rate") + " 钻石");
                    lines.add("不增加准备金，不直接转入个人储蓄。预览有效期 60 秒，账本变化后必须重算。");
                    button("确认按预览发行", x, height - 30, w, () -> BankUiClient.send("admin-policy-confirm"));
                    confirmation.active = dev.jdguo.bank.ledger.CentralIssuePlan.sameTarget(typed, BankSnapshot.text(quote, "target"));
                }
            }
        } else if (page == 2) {
            if ("note".equals(selected)) selected = "diamond";
            button(("copper".equals(selected) ? "▶ " : "") + "铜锭", x, 60, (w - 4) / 2,
                    () -> { selected = "copper"; clearAndInit(); });
            button(("diamond".equals(selected) ? "▶ " : "") + "钻石", x + (w + 4) / 2, 60, (w - 4) / 2,
                    () -> { selected = "diamond"; clearAndInit(); });
            textTop = 90;
            lines.add(name() + "央行自有储蓄：" + centralField("treasury"));
            lines.add(name() + "玩家存款保管池：" + centralField("vault"));
            lines.add(name() + "现有准备金：" + centralField("reserve"));
            lines.add("三个账户分开记账。注资明确扣除操作者本人储蓄；不会自动取用其他玩家资产。旧存档的自有储蓄从零开始，不挪用保管池。");
            lines.add("目标准备金划转：暂未开放。须先确认全市场 M0/M1/M2 的汇率统计口径，不能把嵌套指标相加或套用旧公式。现有余额保持不变。");
            button("从本人储蓄注资", x, height - 30, (w - 4) / 2, () -> switchPage(6));
            button("自有储蓄取回", x + (w + 4) / 2, height - 30, (w - 4) / 2, () -> switchPage(7));
        } else if (page == 3) {
            input("回收数量（" + centralName() + "）", x, w);
            button("预览回收", x + w - 112, 86, 112, () -> {
                remember();
                if (BankRates.parse(typed) != null && BankRates.parse(typed).signum() > 0) {
                    pending = BankActions.adminRetire("note", typed); clearAndInit();
                }
            });
            textTop = 118;
            lines.add("仅回收未分配的央行券，不扣除他人流通余额。");
            if (pending != null) {
                lines.add("确认回收 " + typed + " " + centralName() + "？服务端会重新检查可回收数量。");
                button("确认回收", x, height - 30, w, () -> { String command = pending; pending = null; BankUiClient.send(command); });
            }
        } else if (page == 4) {
            lines.add(BankBook.freezeButton(data));
            lines.add("兑付设置只控制央行券兑付，不修改余额、币名或准备金。");
            if (pending == null) button("调整兑付状态", x, height - 30, w, () -> {
                Boolean frozen = BankSnapshot.frozen(data);
                if (frozen != null) { pending = "admin-freeze-set " + frozen + " " + !frozen; clearAndInit(); }
            });
            else {
                lines.add("确认目标：" + (pending.endsWith(" true") ? "暂停兑付" : "恢复兑付"));
                button("确认调整", x, height - 30, w, () -> { String command = pending; pending = null; BankUiClient.send(command); });
            }
        } else if (page == 8 || page == 9) {
            selected = "note";
            String account = page == 8 ? "shop" : "land";
            String title = page == 8 ? "商店系统账户" : "地产系统账户";
            input("拨款数量（" + centralName() + "）", x, w);
            button("预览拨款", x + w - 112, 86, 112, () -> {
                remember(); BankUiClient.send("admin-funding-preview " + account + " " + typed);
            });
            textTop = 118;
            lines.add(title + "（仅列 " + centralName() + "，不含其他抵押物折价）");
            JsonObject funds = data.has(account + "SystemFunds") ? data.getAsJsonObject(account + "SystemFunds") : new JsonObject();
            lines.add("可用央行券：" + BankSnapshot.text(funds, "available"));
            lines.add("经营储蓄（拨款入账处）：" + BankSnapshot.text(funds, "ordinary"));
            lines.add("结算资产中的央行券：" + BankSnapshot.text(funds, "settled"));
            lines.add("待交付入账锁定：" + BankSnapshot.text(funds, "heldIncoming") + "（已从可用额扣除）");
            lines.add("待完成付款：" + BankSnapshot.text(funds, "pendingOutgoing") + "（已转出，不计入上述余额）");
            lines.add("央行自有储蓄：" + centralField("treasury") + " " + centralName());
            lines.add("只从央行自有央行券拨入此系统账户，不使用玩家存款，不自动增发。两个系统账户独立。");
            button("央行券注资", x, height - 30, (w - 4) / 2, () -> switchPage(6));
            JsonObject preview = data.has("fundingPreview") ? data.getAsJsonObject("fundingPreview") : null;
            if (preview != null && account.equals(BankSnapshot.text(preview, "account"))) {
                lines.add("拨款：" + BankSnapshot.text(preview, "amount") + " " + centralName());
                lines.add("拨款后央行自有：" + BankSnapshot.text(preview, "sourceAfter"));
                lines.add("拨款后经营储蓄：" + BankSnapshot.text(preview, "targetAfter"));
                lines.add("预览有效 60 秒；账户余额变化需重新预览。");
                button("确认拨款", x + (w + 4) / 2, height - 30, (w - 4) / 2, () -> BankUiClient.send("admin-funding-confirm"));
                java.math.BigDecimal current = BankRates.parse(typed), quoted = BankRates.parse(BankSnapshot.text(preview, "amount"));
                confirmation.active = current != null && quoted != null && current.compareTo(quoted) == 0;
            }
            if (data.has("systemFundingLog")) {
                lines.add("最近拨款记录");
                var entries = data.getAsJsonArray("systemFundingLog"); int shown = 0;
                for (int i = entries.size() - 1; i >= 0 && shown < 5; i--) {
                    var entry = entries.get(i).getAsJsonObject();
                    if (!account.equals(BankSnapshot.text(entry, "account"))) continue;
                    lines.add("+" + BankSnapshot.text(entry, "amount") + "，经营储蓄 " + BankSnapshot.text(entry, "accountAfter")); shown++;
                }
                if (shown == 0) lines.add("暂无拨款记录");
            }
        } else if (page == 6 || page == 7) {
            input("数量（" + name() + ("note".equals(selected) ? "）" : "，整数）"), x, w);
            button("预览划转", x + w - 112, 86, 112, () -> {
                remember();
                java.math.BigDecimal quantity = BankRates.parse(typed);
                if (quantity != null && quantity.signum() > 0 && ("note".equals(selected) || quantity.stripTrailingZeros().scale() <= 0)) {
                    pending = (page == 6 ? "admin-treasury-deposit " : "admin-treasury-withdraw ") + selected + " " + typed;
                    BankUiClient.send("admin-treasury-prepare " + selected + " " + typed + (page == 6 ? " deposit" : " withdraw"));
                    clearAndInit();
                }
            });
            textTop = 118;
            lines.add("央行自有储蓄：" + centralField("treasury") + " " + name());
            lines.add(page == 6 ? "来源：操作者本人储蓄。去向：央行自有储蓄。" : "来源：央行自有储蓄。去向：操作者本人储蓄。");
            lines.add("实物仅支持整数；央行券最多四位小数。余额不足整笔拒绝，不从其他玩家存款垫付。");
            if (pending != null) {
                lines.add("确认划转 " + typed + " " + name() + "？");
                button("确认划转", x, height - 30, w, () -> { String command = pending; pending = null; BankUiClient.send(command); });
            }
        } else {
            input("央行券显示名称（最多 16 字）", x, w);
            field.setTextPredicate(t -> true); field.setMaxLength(16); field.setText(renameDraft == null ? centralName() : renameDraft);
            textTop = 118;
            lines.add("仅更改显示名称，不改变币种标识、现有余额或历史记录。");
            button("保存名称", x, height - 30, w, () -> BankUiClient.send("admin-rename " + field.getText().trim()));
        }
    }
    @Override public void resize(net.minecraft.client.MinecraftClient client, int width, int height) {
        remember(); super.resize(client, width, height);
    }
    private String centralField(String key) {
        if (data.has("central")) for (var item : data.getAsJsonArray("central")) {
            var row = item.getAsJsonObject();
            if (selected.equals(BankSnapshot.text(row, "id"))) {
                String value = BankSnapshot.text(row, key);
                return value == null ? "暂无数据" : value;
            }
        }
        return "暂无数据";
    }
    private void input(String label, int x, int w) {
        field = new TextFieldWidget(textRenderer, x, 86, w - 120, 20, Text.literal(label));
        field.setMaxLength(32); field.setTextPredicate(BankAdminScreen::amountChars); field.setText(typed);
        field.setChangedListener(value -> {
            if (confirmation != null) confirmation.active = false;
            pending = null;
        });
        addDrawableChild(field); setFocused(field); field.setFocused(true);
    }
    private void remember() { if (field != null) { if (page == 5) renameDraft = field.getText(); else typed = field.getText().trim(); } }
    @Override public void tick() { if (field != null) field.tick(); }
    @Override public boolean mouseScrolled(double x, double y, double amount) {
        scroll = Math.max(0, scroll - (int) Math.signum(amount) * 22); return true;
    }
    @Override public void render(DrawContext context, int mx, int my, float delta) {
        renderBackground(context);
        int x = left(), w = bodyWidth();
        context.drawCenteredTextWithShadow(textRenderer, "央行管理", width / 2, 12, 0xFFE8D090);
        if (field != null) {
            String label = page == 1 ? "目标：钻石 / 1 " + centralName() : page == 3 ? "回收数量（" + centralName() + "）" : page == 8 || page == 9 ? "系统账户拨款（" + centralName() + "）" : page == 6 || page == 7 ? "划转数量（" + name() + ("note".equals(selected) ? "）" : "，整数）") : "央行券显示名称";
            context.drawText(textRenderer, label, x, 68, 0xFFE8D090, false);
        }
        int contentHeight = 0;
        for (String line : lines) contentHeight += Math.max(1, textRenderer.wrapLines(Text.literal(line == null ? "" : line), w).size()) * 12 + 4;
        scroll = Math.min(scroll, Math.max(0, contentHeight - (height - 46 - textTop)));
        context.enableScissor(x, textTop, x + w, Math.max(textTop, height - 46));
        int y = textTop - scroll;
        for (String line : lines) {
            var wrapped = textRenderer.wrapLines(Text.literal(line == null ? "" : line), w);
            if (wrapped.isEmpty()) y += 12;
            for (var row : wrapped) { context.drawText(textRenderer, row, x, y, 0xFFE6E6E6, false); y += 12; }
            y += 4;
        }
        context.disableScissor();
        if (contentHeight > height - 46 - textTop) context.drawText(textRenderer, "滚轮查看详情", x, height - 42, 0xFFAAAAAA, false);
        super.render(context, mx, my, delta);
    }
    static String amountButton(String action, String typed, String currency) {
        String amount = typed == null ? "" : typed.trim();
        return action + (amount.isEmpty() ? "" : " " + amount) + " " + currency;
    }
    static boolean amountChars(String text) { return text != null && (text.isEmpty() || text.matches("\\d*(\\.\\d{0,4})?")); }
    @Override public void close() {
        if (!closing) { closing = true; BankUiClient.send(BankActions.close()); }
        super.close();
    }
    @Override public boolean shouldPause() { return false; }
}
