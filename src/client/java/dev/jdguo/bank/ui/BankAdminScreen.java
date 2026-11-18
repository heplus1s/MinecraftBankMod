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
    private String adjustmentMode = "quantity";
    private String adjustmentDirection = "increase";
    private String reserveCurrency = "diamond";
    private int inputY = 86;
    private String inputLabel;

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
    private int left() { return Math.max(10, (width - bodyWidth()) / 2); }
    private int bodyWidth() { return Math.min(page == 0 ? 720 : 420, width - 20); }
    private void button(String label, int x, int y, int w, Runnable action) {
        ButtonWidget button = addDrawableChild(ButtonWidget.builder(Text.literal(textRenderer.trimToWidth(label, Math.max(1, w - 8))), b -> action.run()).dimensions(x, y, w, 20).build());
        if (label.startsWith("确认")) confirmation = button;
    }
    private void switchPage(int next) {
        remember(); page = next; scroll = 0; pending = null;
        data.remove("centralAdjustmentPreview");
        BankUiClient.send("admin-policy-cancel");
        BankUiClient.send("admin-adjust-cancel");
        BankUiClient.send("admin-funding-cancel");
        clearAndInit();
    }
    @Override protected void init() {
        field = null; confirmation = null; lines.clear(); inputY = 86; inputLabel = null;
        int x = left(), w = bodyWidth();
        if (width < 250 || height < 230) {
            textTop = 42;
            lines.add("窗口过小，请降低 GUI 缩放或放大游戏窗口后管理央行。");
            button("关闭", x, height - 28, w, this::close);
            return;
        }
        button("更名设置", x, 6, 76, () -> switchPage(5));
        button("玩家存款", x + 80, 6, 76, () -> BankUiClient.send("admin-players 0"));
        button("关闭", x + w - 50, 6, 50, this::close);
        String[] tabs = {"总览", "发行管理", "准备金", "回收", "兑付"};
        int tabW = (w - 16) / 5;
        for (int i = 0; i < tabs.length; i++) {
            final int tab = i;
            button((page == i ? "• " : "") + tabs[i], x + i * (tabW + 4), 32, tabW, () -> switchPage(tab));
        }
        textTop = 64;
        if (page == 0) {
            // All currencies and aggregates share one measured dashboard; no long text scroller.
            textTop = 60;
            button("商店储蓄管理", x, height - 30, (w - 4) / 2, () -> switchPage(8));
            button("地产储蓄管理", x + (w + 4) / 2, height - 30, (w - 4) / 2, () -> switchPage(9));
        } else if (page == 1) {
            adjustmentPage(x, w);
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
            lines.add("三个账户分开记账。注资明确扣除操作者本人储蓄；不会自动取用其他玩家资产。旧存档仅将已发行但未归属的央行券归入自有储蓄，不挪用保管池。");
            lines.add("按目标汇率划转准备金：请进入“发行管理 → 按汇率调准备金”。仅使用央行自有储蓄，保持现行汇率公式。");
            button("从本人储蓄注资", x, height - 30, (w - 4) / 2, () -> switchPage(6));
            button("自有储蓄取回", x + (w + 4) / 2, height - 30, (w - 4) / 2, () -> switchPage(7));
        } else if (page == 3) {
            adjustmentMode = "quantity";
            adjustmentDirection = "decrease";
            adjustmentPage(x, w);
        } else if (page == 4) {
            lines.add(BankBook.redemption(data));
            lines.add("当前运行状况：" + value(data, "status"));
            lines.add(BankRates.bankNoteQuote(data));
            lines.addAll(BankBook.redemptionDetails(data));
            lines.add("当前兑换差价：" + value(data, "feePercent"));
            lines.add("下一步操作：" + BankBook.freezeButton(data));
            lines.add("兑付设置只控制央行券兑付，不修改余额、币名或准备金。");
            if (pending == null) button(BankBook.freezeButton(data), x, height - 30, w, () -> {
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
    private void changeAdjustment(String mode, String direction, String currency) {
        remember();
        adjustmentMode = mode; adjustmentDirection = direction; reserveCurrency = currency;
        scroll = 0;
        data.remove("centralAdjustmentPreview");
        BankUiClient.send("admin-adjust-cancel");
        clearAndInit();
    }
    private static String value(JsonObject source, String key) {
        String raw = BankSnapshot.text(source, key);
        return raw == null || raw.isBlank() ? "暂无数据" : raw;
    }
    private void adjustmentPage(int x, int w) {
        int half = (w - 4) / 2;
        if (page != 3) {
            button(("quantity".equals(adjustmentMode) ? "▶ " : "") + "货币数量增减", x, 60, half,
                    () -> changeAdjustment("quantity", adjustmentDirection, reserveCurrency));
            button(("reserve".equals(adjustmentMode) ? "▶ " : "") + "按汇率调准备金", x + half + 4, 60, half,
                    () -> changeAdjustment("reserve", adjustmentDirection, reserveCurrency));
        }
        boolean quantity = "quantity".equals(adjustmentMode);
        if (quantity) {
            button(("increase".equals(adjustmentDirection) ? "▶ " : "") + "增加货币", x, 84, half,
                    () -> { if (page == 3) page = 1; changeAdjustment("quantity", "increase", reserveCurrency); });
            button(("decrease".equals(adjustmentDirection) ? "▶ " : "") + "减少货币", x + half + 4, 84, half,
                    () -> changeAdjustment("quantity", "decrease", reserveCurrency));
        } else {
            button(("diamond".equals(reserveCurrency) ? "▶ " : "") + "调整钻石准备金", x, 84, half,
                    () -> changeAdjustment("reserve", adjustmentDirection, "diamond"));
            button(("copper".equals(reserveCurrency) ? "▶ " : "") + "调整铜锭准备金", x + half + 4, 84, half,
                    () -> changeAdjustment("reserve", adjustmentDirection, "copper"));
        }
        inputY = 130;
        input(quantity ? "调整数量（" + centralName() + "，整数）" : "目标汇率（钻石 / 1 " + centralName() + "）", x, w);
        button("计算调整预览", x + w - 112, inputY, 112, () -> {
            remember();
            BankUiClient.send("admin-adjust-preview " + adjustmentMode + " "
                    + (quantity ? adjustmentDirection : reserveCurrency) + " " + typed);
        });
        textTop = 162;
        lines.add("当前：" + BankBook.redemption(data));
        lines.add(BankRates.bankNoteQuote(data));
        for (var row : BankSnapshot.central(data)) {
            if ("note".equals(row.id())) {
                lines.add("当前发行总额：" + row.liability());
                lines.add("央行自有央行券（可拨款/回收）：" + row.treasury());
            }
            if ("diamond".equals(row.id()) || "copper".equals(row.id()))
                lines.add(row.name() + "准备金：" + row.reserve() + "；央行自有储蓄：" + row.treasury());
        }
        lines.add(quantity ? "增发央行券进入央行自有储蓄，可用于商店及地产拨款；减少仅从央行自有储蓄回收，不扣玩家或系统账户余额。"
                : "发行数量不变，只在央行自有储蓄与准备金间划转，不动玩家存款。实物按整数划转，实际汇率以预览为准。");
        JsonObject q = data.has("centralAdjustmentPreview") ? data.getAsJsonObject("centralAdjustmentPreview") : null;
        if (q == null) return;
        String rejection = BankAdjustmentView.rejection(q);
        if (rejection != null) {
            lines.add(rejection);
            return;
        }
        if (!adjustmentMode.equals(value(q, "mode"))) return;
        lines.add("调整预览：" + value(q, "reason"));
        lines.add("汇率：" + value(q, "rateBefore") + " → " + value(q, "rateAfter") + " 钻石 / " + centralName());
        lines.add("发行总额：" + value(q, "liabilitiesBefore") + " → " + value(q, "liabilitiesAfter"));
        if (quantity) lines.add("央行自有央行券：" + value(q, "treasuryBefore") + " → " + value(q, "treasuryAfter"));
        if (!quantity) {
            String unit = "diamond".equals(reserveCurrency) ? "钻石" : "铜锭";
            lines.add(unit + "准备金：" + value(q, "reserveBefore") + " → " + value(q, "reserveAfter"));
            lines.add(unit + "央行自有储蓄：" + value(q, "treasuryBefore") + " → " + value(q, "treasuryAfter"));
        }
        lines.add("预览有效 60 秒；账本变化或修改输入后需重新计算。");
        if (q.has("allowed") && q.get("allowed").getAsBoolean()) {
            String token = BankSnapshot.text(q, "token");
            button("确认此次调整", x, height - 30, w, () -> {
                if (token == null || token.isBlank()) return;
                confirmation.active = false;
                BankUiClient.send("admin-adjust-confirm " + token);
            });
            String expected = value(q, quantity ? "amount" : "target");
            var a = BankRates.parse(typed); var b = BankRates.parse(expected);
            confirmation.active = token != null && !token.isBlank() && a != null && b != null && a.compareTo(b) == 0
                    && (quantity ? adjustmentDirection.equals(value(q, "direction")) : reserveCurrency.equals(value(q, "currency")));
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
        inputLabel = label;
        field = new TextFieldWidget(textRenderer, x, inputY, w - 120, 20, Text.literal(label));
        field.setMaxLength(32); field.setTextPredicate(BankAdminScreen::amountChars); field.setText(typed);
        field.setChangedListener(value -> {
            if (confirmation != null) confirmation.active = false;
            pending = null;
            if (page == 1 || page == 3) {
                data.remove("centralAdjustmentPreview");
                BankUiClient.send("admin-adjust-cancel");
            }
        });
        addDrawableChild(field); setFocused(field); field.setFocused(true);
    }
    private void remember() { if (field != null) { if (page == 5) renameDraft = field.getText(); else typed = field.getText().trim(); } }
    @Override public void tick() { if (field != null) field.tick(); }
    @Override public boolean mouseScrolled(double x, double y, double amount) {
        if (page != 0) scroll = Math.max(0, scroll - (int) Math.signum(amount) * 22); return true;
    }
    @Override public void render(DrawContext context, int mx, int my, float delta) {
        renderBackground(context);
        int x = left(), w = bodyWidth();
        if (w >= 360) context.drawCenteredTextWithShadow(textRenderer, "央行管理", width / 2, 12, 0xFFE8D090);
        if (field != null) {
            context.drawText(textRenderer, textRenderer.trimToWidth(inputLabel == null ? "" : inputLabel, w), x, inputY - 16, 0xFFE8D090, false);
        }
        if (page == 0 && width >= 250 && height >= 230) {
            renderOverview(context, mx, my);
            super.render(context, mx, my, delta);
            return;
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
    private void renderOverview(DrawContext context, int mouseX, int mouseY) {
        int x = left(), available = height - 100;
        var layout = BankOverview.layout(data, bodyWidth(), available, textRenderer::getWidth);
        if (!layout.readable()) {
            context.drawCenteredTextWithShadow(textRenderer, "数据较长，请降低 GUI 缩放或放大窗口", width / 2, 76, 0xFFFFCC88);
            return;
        }
        float scale = layout.scale();
        float originX = x + (bodyWidth() - layout.width() * scale) / 2f;
        context.getMatrices().push();
        context.getMatrices().translate(originX, 60, 0);
        context.getMatrices().scale(scale, scale, 1);
        String tooltip = null;
        for (var cell : layout.cells()) {
            context.fill(cell.x(), cell.y(), cell.x() + cell.width(), cell.y() + cell.height(), cell.heading() ? 0xEE354454 : 0xDD202A34);
            for (int i = 0; i < cell.lines().size(); i++) {
                String line = cell.lines().get(i);
                int textX = i >= cell.rightFrom() ? cell.x() + cell.width() - 6 - textRenderer.getWidth(line) : cell.x() + 6;
                context.drawText(textRenderer, line, textX, cell.y() + 4 + i * 12, cell.heading() && i < cell.rightFrom() ? 0xFFE8D090 : 0xFFFFFFFF, false);
            }
            float mx = (mouseX - originX) / scale, my = (mouseY - 60) / scale;
            if (mx >= cell.x() && mx < cell.x() + cell.width() && my >= cell.y() && my < cell.y() + cell.height()) tooltip = cell.help();
        }
        context.getMatrices().pop();
        if (tooltip != null) context.drawTooltip(textRenderer, textRenderer.wrapLines(Text.literal(tooltip), Math.min(300, width - 24)), mouseX, mouseY);
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
