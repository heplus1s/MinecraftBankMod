package dev.jdguo.bank.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Three levels: categories, then a list of display names, then the form.
 * Internal ids stay on the choice object and are never drawn.
 */
public final class BankTerminalScreen extends Screen {
    private enum Level { HOME, LIST, FORM, CONFIRM }

    private JsonObject data;
    private Level level = Level.HOME;
    private String category = "";
    private int step;
    private ChoiceListWidget list;
    private TextFieldWidget amount;
    private TextFieldWidget nameField;
    private String regime = "float";
    private String anchorId = "diamond";
    private String pickedLabel = "";
    private ChoiceListWidget extra;

    public BankTerminalScreen(JsonObject data) {
        super(Text.literal("Central Bank"));
        this.data = data;
    }

    void show(JsonObject data) {
        boolean confirm = data.has("confirm") && data.get("confirm").getAsBoolean();
        this.data = data;
        if (confirm) {
            level = Level.CONFIRM;
        } else if (level == Level.CONFIRM) {
            level = Level.LIST;
            category = "issuance";
        }
        clearAndInit();
    }

    @Override
    protected void init() {
        extra = null;
        int x = this.width / 2 - 210;
        int y = 36;
        if (level == Level.CONFIRM) {
            addDrawableChild(ButtonWidget.builder(Text.literal("返回"), b -> {
                BankUiClient.send("note-bankrupt-no");
                level = Level.LIST;
                category = "issuance";
                step = 0;
            }).dimensions(x, this.height - 40, 80, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("确认宣告破产"), b -> BankUiClient.send("note-bankrupt-yes"))
                    .dimensions(this.width / 2 + 40, this.height - 40, 140, 20).build());
            return;
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("返回"), b -> back()).dimensions(x, this.height - 28, 60, 18).build());
        int listHeight = Math.max(72, Math.min(160, this.height - y - 64));
        list = new ChoiceListWidget(client, x, y + 16, 220, listHeight);
        list.setChoices(choices());
        addSelectableChild(list);
        if ("issuance".equals(category) && data.has("personal") && level == Level.LIST) {
            addDrawableChild(ButtonWidget.builder(Text.literal("宣告破产"), b -> {
                BankUiClient.send("note-bankrupt");
                level = Level.CONFIRM;
                clearAndInit();
            }).dimensions(this.width / 2 + 70, this.height - 28, 100, 18).build());
        }
        if (level != Level.FORM) {
            return;
        }
        amount = field(x + 236, y + 36, 140, "1");
        addDrawableChild(amount);
        if ("deposit".equals(category) || "withdraw".equals(category)) {
            int bx = x + 236;
            for (String label : new String[] {"1", "5", "10", "64"}) {
                String shown = label;
                addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> quick(shown))
                        .dimensions(bx, y + 56, 22, 16).build());
                bx += 24;
            }
            addDrawableChild(ButtonWidget.builder(Text.literal("全部"), b -> quick("全部"))
                    .dimensions(bx, y + 56, 36, 16).build());
        }
        if ("issuance".equals(category) && !data.has("personal")) {
            nameField = field(x + 236, y + 58, 140, "");
            addDrawableChild(nameField);
            extra = new ChoiceListWidget(client, x + 236, y + 96, 140, 54);
            extra.setChoices(List.of(
                    new ChoiceListWidget.Choice("diamond", "钻石"),
                    new ChoiceListWidget.Choice("copper", "铜锭"),
                    new ChoiceListWidget.Choice("note", "银行券")));
            addSelectableChild(extra);
            addDrawableChild(ButtonWidget.builder(Text.literal("登记个人银行券"), b -> {
                String anchor = extra.selectedId() == null ? "diamond" : extra.selectedId();
                BankUiClient.send("note-create " + regime + " " + anchor + " " + nameField.getText().trim() + " " + amount.getText().trim());
            }).dimensions(x + 236, y + 140, 150, 18).build());
            return;
        }
        if ("market".equals(category) && ("swap".equals(regime) || "redeem".equals(regime))) {
            ChoiceListWidget extra = new ChoiceListWidget(client, x + 236, y + 58, 160, 70);
            extra.setChoices("swap".equals(regime) ? notes() : currencies());
            addSelectableChild(extra);
            this.extra = extra;
        }
        String verb = switch (category) {
            case "deposit" -> "存入";
            case "withdraw" -> "取出";
            case "exchange" -> "兑换";
            case "checks" -> "开出银行汇票";
            case "market" -> marketVerb();
            case "issuance" -> issuanceVerb();
            default -> "确认办理";
        };
        int verbY = "deposit".equals(category) || "withdraw".equals(category) ? y + 80 : y + 136;
        addDrawableChild(ButtonWidget.builder(Text.literal(verb), b -> submit()).dimensions(x + 236, verbY, 210, 18).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        int x = this.width / 2 - 210;
        context.drawText(textRenderer, titleLine(), x, 12, 0xFFD0B060, false);
        if ("management".equals(category) && level != Level.CONFIRM) {
            context.drawText(textRenderer, "M0 " + text("m0") + "   M1 " + text("m1") + "   M2 " + text("m2"), x, 24, 0xFFE8E8E8, false);
        } else if (level == Level.HOME) {
            context.drawText(textRenderer, "状态 " + text("status"), x, 24, 0xFFB7C7B0, false);
        }
        if (level == Level.CONFIRM) {
            context.drawText(textRenderer, "确认后才会作废「" + text("personal") + "」。返回则账目不变。", x, 80, 0xFFFF7777, false);
            return;
        }
        if (list != null) {
            list.render(context, mouseX, mouseY, delta);
        }
        if (extra != null) {
            extra.render(context, mouseX, mouseY, delta);
        }
        if (level != Level.FORM) {
            renderFigures(context, x + 236, 70);
        } else if (level == Level.FORM) {
            context.drawText(textRenderer, pickedLabel.isBlank() ? "数量" : pickedLabel, x + 236, 52, 0xFFE8D090, false);
            if ("market".equals(category) && "swap".equals(regime)) {
                context.drawText(textRenderer, "换入的个人银行券", x + 236, 78, 0xFFAAAAAA, false);
            } else if ("market".equals(category) && "redeem".equals(regime)) {
                context.drawText(textRenderer, "兑付为", x + 236, 78, 0xFFAAAAAA, false);
            } else if ("issuance".equals(category) && !data.has("personal")) {
                context.drawText(textRenderer, "名称", x + 236, 78, 0xFFAAAAAA, false);
            }
        }
    }

    private void renderFigures(DrawContext context, int x, int y) {
        if ("management".equals(category)) {
            y = line(context, "储蓄总池", x, y, 0xFFB7C7B0);
            y = centralLines(context, x, y, "savings");
            y = line(context, "发行准备金", x, y + 4, 0xFFD0B060);
            centralLines(context, x, y, "reserve");
            return;
        }
        if ("issuance".equals(category) && data.has("personal")) {
            y = line(context, "「" + text("personal") + "」", x, y, 0xFFE8D090);
            y = line(context, "流通额  " + text("circulation"), x, y, 0xFFE8E8E8);
            line(context, "准备金账户  " + text("reserve") + "  应当存入  " + text("required"), x, y, 0xFFE8E8E8);
            return;
        }
        if (level == Level.HOME || "savings".equals(category) || "deposit".equals(category) || "withdraw".equals(category)) {
            y = line(context, "储蓄账户", x, y, 0xFFD0B060);
            if (!data.has("accounts")) {
                return;
            }
            JsonArray rows = data.getAsJsonArray("accounts");
            for (int i = 0; i < rows.size(); i++) {
                JsonObject row = rows.get(i).getAsJsonObject();
                y = line(context, row.get("name").getAsString() + "  " + row.get("amount").getAsString(), x, y, 0xFFE8E8E8);
            }
        }
    }

    private int centralLines(DrawContext context, int x, int y, String field) {
        if (!data.has("central")) {
            return y;
        }
        JsonArray rows = data.getAsJsonArray("central");
        for (int i = 0; i < rows.size(); i++) {
            JsonObject row = rows.get(i).getAsJsonObject();
            if (!row.has(field)) {
                continue;
            }
            y = line(context, row.get("name").getAsString() + "  " + row.get(field).getAsString(), x, y, 0xFFE8E8E8);
        }
        return y;
    }

    private int line(DrawContext context, String text, int x, int y, int color) {
        context.drawText(textRenderer, text, x, y, color, false);
        return y + 11;
    }

    private String titleLine() {
        return switch (level) {
            case HOME -> "中央银行";
            case LIST -> categoryTitle();
            case FORM -> categoryTitle();
            case CONFIRM -> "确认宣告破产";
        };
    }

    private String categoryTitle() {
        return switch (category) {
            case "savings" -> "储蓄";
            case "deposit" -> "存入";
            case "withdraw" -> "取出";
            case "exchange" -> step == 0 ? "兑换付出的货币" : "兑换得到的货币";
            case "checks" -> "银行汇票";
            case "market" -> step == 0 ? "选择个人银行券" : "交易方式";
            case "issuance" -> data.has("personal") ? "个人货币发行" : "个人货币发行的汇率";
            case "management" -> "中央银行管理";
            default -> "中央银行";
        };
    }

    private List<ChoiceListWidget.Choice> choices() {
        if (level == Level.HOME) {
            List<ChoiceListWidget.Choice> rows = new ArrayList<>();
            rows.add(new ChoiceListWidget.Choice("savings", "储蓄"));
            rows.add(new ChoiceListWidget.Choice("deposit", "存入"));
            rows.add(new ChoiceListWidget.Choice("withdraw", "取出"));
            rows.add(new ChoiceListWidget.Choice("exchange", "兑换"));
            rows.add(new ChoiceListWidget.Choice("checks", "银行汇票"));
            rows.add(new ChoiceListWidget.Choice("market", "个人银行券交易"));
            rows.add(new ChoiceListWidget.Choice("issuance", "个人货币发行"));
            if (data.has("admin") && data.get("admin").getAsBoolean()) {
                rows.add(new ChoiceListWidget.Choice("management", "中央银行管理"));
            }
            return rows;
        }
        if ("market".equals(category) && step == 1) {
            return List.of(
                    new ChoiceListWidget.Choice("buy", "买入"),
                    new ChoiceListWidget.Choice("sell", "卖出"),
                    new ChoiceListWidget.Choice("swap", "互换"),
                    new ChoiceListWidget.Choice("redeem", "兑付"));
        }
        if ("issuance".equals(category) && !data.has("personal")) {
            return List.of(
                    new ChoiceListWidget.Choice("float", "浮动汇率"),
                    new ChoiceListWidget.Choice("peg", "钉住汇率"),
                    new ChoiceListWidget.Choice("hard", "定额兑付"));
        }
        if ("issuance".equals(category)) {
            return List.of(
                    new ChoiceListWidget.Choice("issue", "按发行准备金货币发行"),
                    new ChoiceListWidget.Choice("unbacked", "无发行准备金的货币发行"),
                    new ChoiceListWidget.Choice("reserve-deposit", "存入准备金账户"),
                    new ChoiceListWidget.Choice("reserve-withdraw", "取出准备金账户"));
        }
        if ("management".equals(category)) {
            return List.of(
                    new ChoiceListWidget.Choice("freeze", "暂停或恢复银行券兑付"),
                    new ChoiceListWidget.Choice("unbacked", "无发行准备金的货币发行"),
                    new ChoiceListWidget.Choice("retire", "注销银行券"));
        }
        if ("market".equals(category)) {
            return notes();
        }
        return currencies();
    }

    private List<ChoiceListWidget.Choice> currencies() {
        List<ChoiceListWidget.Choice> rows = new ArrayList<>();
        if (!data.has("currencies")) {
            return rows;
        }
        JsonArray array = data.getAsJsonArray("currencies");
        for (int i = 0; i < array.size(); i++) {
            JsonObject row = array.get(i).getAsJsonObject();
            rows.add(new ChoiceListWidget.Choice(row.get("id").getAsString(),
                    row.get("name").getAsString() + "  " + row.get("balance").getAsString()));
        }
        return rows;
    }

    private List<ChoiceListWidget.Choice> notes() {
        List<ChoiceListWidget.Choice> rows = new ArrayList<>();
        if (!data.has("notes")) {
            return rows;
        }
        JsonArray array = data.getAsJsonArray("notes");
        for (int i = 0; i < array.size(); i++) {
            JsonObject row = array.get(i).getAsJsonObject();
            rows.add(new ChoiceListWidget.Choice(row.get("name").getAsString(),
                    row.get("name").getAsString() + "  " + row.get("balance").getAsString()));
        }
        return rows;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (list != null && list.isMouseOver(mouseX, mouseY) && list.mouseClicked(mouseX, mouseY, button)) {
            if (mouseX < list.getRowRight() - 8) {
                ChoiceListWidget.Choice picked = selected();
                if (picked != null && level != Level.FORM) {
                    choose(picked);
                }
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private ChoiceListWidget.Choice selected() {
        if (list == null || list.getSelectedOrNull() == null) {
            return null;
        }
        String id = list.selectedId();
        String label = list.selectedLabel();
        return new ChoiceListWidget.Choice(id, label);
    }

    private void choose(ChoiceListWidget.Choice picked) {
        if (level == Level.HOME) {
            category = picked.id();
            step = 0;
            level = Level.LIST;
            clearAndInit();
            return;
        }
        if ("deposit".equals(category) || "withdraw".equals(category)) {
            pickedLabel = picked.label();
            level = Level.FORM;
            anchorId = picked.id();
            clearAndInit();
            return;
        }
        if ("exchange".equals(category)) {
            if (step == 0) {
                anchorId = picked.id();
                step = 1;
                clearAndInit();
                return;
            }
            pickedLabel = picked.label();
            regime = picked.id();
            level = Level.FORM;
            clearAndInit();
            return;
        }
        if ("issuance".equals(category) && !data.has("personal")) {
            pickedLabel = picked.label();
            regime = picked.id();
            level = Level.FORM;
            clearAndInit();
            return;
        }
        if ("issuance".equals(category) && "bankrupt".equals(picked.id())) {
            BankUiClient.send("note-bankrupt");
            level = Level.CONFIRM;
            clearAndInit();
            return;
        }
        if ("market".equals(category) && step == 0) {
            anchorId = picked.id();
            step = 1;
            clearAndInit();
            return;
        }
        pickedLabel = picked.label();
        regime = picked.id();
        level = Level.FORM;
        clearAndInit();
    }

    private void quick(String shown) {
        if (amount != null) {
            amount.setText(shown);
        }
        submit();
    }

    private String qtyToken() {
        String raw = amount == null || amount.getText().isBlank() ? "1" : amount.getText().trim();
        if ("全部".equals(raw) || "all".equalsIgnoreCase(raw) || "max".equalsIgnoreCase(raw)) {
            return "all";
        }
        return raw;
    }

    private void submit() {
        String qty = qtyToken();
        switch (category) {
            case "deposit" -> BankUiClient.send("deposit-transfer " + anchorId + " " + qty);
            case "withdraw" -> BankUiClient.send("withdraw " + anchorId + " " + qty);
            case "exchange" -> BankUiClient.send("exchange " + anchorId + " " + regime + " " + qty);
            case "checks" -> BankUiClient.send("check " + regime + " " + qty + " bearer");
            case "market" -> {
                String note = anchorId;
                String other = extra == null || extra.selectedId() == null ? "" : extra.selectedId();
                if ("swap".equals(regime)) {
                    if (!other.isBlank()) {
                        BankUiClient.send("note-swap " + note + " " + other + " " + qty);
                    }
                } else if ("redeem".equals(regime)) {
                    if (!other.isBlank()) {
                        BankUiClient.send("note-redeem " + note + " " + other + " " + qty);
                    }
                } else if ("sell".equals(regime)) {
                    BankUiClient.send("note-sell " + note + " " + qty);
                } else {
                    BankUiClient.send("note-buy " + note + " " + qty);
                }
            }
            case "issuance" -> {
                if ("unbacked".equals(regime)) {
                    BankUiClient.send("note-unbacked " + qty);
                } else if ("reserve-deposit".equals(regime)) {
                    BankUiClient.send("reserve-deposit " + qty);
                } else if ("reserve-withdraw".equals(regime)) {
                    BankUiClient.send("reserve-withdraw " + qty);
                } else {
                    BankUiClient.send("note-issue " + qty);
                }
            }
            case "management" -> {
                if ("freeze".equals(regime)) {
                    BankUiClient.send("admin-freeze");
                } else if ("retire".equals(regime)) {
                    BankUiClient.send("admin-retire note " + qty);
                } else {
                    BankUiClient.send("admin-unbacked note " + qty);
                }
            }
            default -> {
            }
        }
    }

    private String marketVerb() {
        return switch (regime) {
            case "sell" -> "卖出";
            case "swap" -> "互换";
            case "redeem" -> "兑付";
            default -> "买入";
        };
    }

    private String issuanceVerb() {
        return switch (regime) {
            case "unbacked" -> "无发行准备金的货币发行";
            case "reserve-deposit" -> "存入准备金账户";
            case "reserve-withdraw" -> "取出准备金账户";
            default -> "货币发行";
        };
    }

    private void back() {
        if (level == Level.FORM) {
            level = Level.LIST;
        } else if (level == Level.LIST && step > 0) {
            step--;
        } else if (level == Level.LIST) {
            level = Level.HOME;
            category = "";
        } else {
            BankUiClient.send("close");
            close();
            return;
        }
        clearAndInit();
    }

    private String text(String key) {
        return data.has(key) ? data.get(key).getAsString() : "-";
    }

    private TextFieldWidget field(int x, int y, int width, String value) {
        TextFieldWidget widget = new TextFieldWidget(textRenderer, x, y, width, 16, Text.literal(""));
        widget.setText(value);
        return widget;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
