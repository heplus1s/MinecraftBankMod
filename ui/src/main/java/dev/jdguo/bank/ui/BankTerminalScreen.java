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
        int x = this.width / 2 - 160;
        int y = 36;
        if (level == Level.CONFIRM) {
            addDrawableChild(ButtonWidget.builder(Text.literal("返回"), b -> {
                BankUiClient.send("note-bankrupt-no");
                level = Level.LIST;
                category = "issuance";
                step = 0;
            }).dimensions(x, this.height - 40, 80, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("确认宣告破产"), b -> BankUiClient.send("note-bankrupt-yes"))
                    .dimensions(this.width / 2 + 60, this.height - 40, 120, 20).build());
            return;
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("返回"), b -> back()).dimensions(x, this.height - 28, 60, 18).build());
        int listHeight = Math.max(72, Math.min(160, this.height - y - 64));
        list = new ChoiceListWidget(client, x, y + 16, 160, listHeight);
        list.setChoices(choices());
        addSelectableChild(list);
        if ("issuance".equals(category) && data.has("personal") && level == Level.LIST) {
            addDrawableChild(ButtonWidget.builder(Text.literal("宣告破产"), b -> {
                BankUiClient.send("note-bankrupt");
                level = Level.CONFIRM;
                clearAndInit();
            }).dimensions(this.width / 2 + 90, this.height - 28, 80, 18).build());
        }
        if (level != Level.FORM) {
            return;
        }
        amount = field(x + 180, y + 36, 120, "1");
        addDrawableChild(amount);
        if ("issuance".equals(category) && !data.has("personal")) {
            nameField = field(x + 180, y + 58, 120, "");
            addDrawableChild(nameField);
            extra = new ChoiceListWidget(client, x + 180, y + 96, 140, 54);
            extra.setChoices(List.of(
                    new ChoiceListWidget.Choice("diamond", "钻石"),
                    new ChoiceListWidget.Choice("copper", "铜锭"),
                    new ChoiceListWidget.Choice("note", "银行券")));
            addSelectableChild(extra);
            addDrawableChild(ButtonWidget.builder(Text.literal("记名"), b -> {
                String anchor = extra.selectedId() == null ? "diamond" : extra.selectedId();
                BankUiClient.send("note-create " + regime + " " + anchor + " " + nameField.getText().trim() + " " + amount.getText().trim());
            }).dimensions(x + 180, y + 140, 120, 18).build());
            return;
        }
        if ("market".equals(category) && ("swap".equals(regime) || "redeem".equals(regime))) {
            ChoiceListWidget extra = new ChoiceListWidget(client, x + 180, y + 58, 140, 70);
            extra.setChoices("swap".equals(regime) ? notes() : currencies());
            addSelectableChild(extra);
            this.extra = extra;
        }
        String verb = switch (category) {
            case "deposit" -> "划拨存入";
            case "withdraw" -> "划拨取出";
            case "exchange" -> "兑换";
            case "checks" -> "开出汇票";
            case "market" -> marketVerb();
            case "issuance" -> issuanceVerb();
            default -> "执行";
        };
        addDrawableChild(ButtonWidget.builder(Text.literal(verb), b -> submit()).dimensions(x + 180, y + 136, 120, 18).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        int x = this.width / 2 - 160;
        context.drawText(textRenderer, titleLine(), x, 12, 0xFFD0B060, false);
        context.drawText(textRenderer, "M0 " + text("m0") + "   M1 " + text("m1") + "   M2 " + text("m2"), x, 24, 0xFFE6E6E6, false);
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
        if (level == Level.HOME) {
            context.drawText(textRenderer, "储蓄总池 " + text("savings") + "  状态 " + text("status"), x + 180, 70, 0xFFB7C7B0, false);
        } else if (level == Level.FORM) {
            context.drawText(textRenderer, pickedLabel.isBlank() ? "数量" : pickedLabel, x + 180, 52, 0xFFE8D090, false);
            if ("market".equals(category) && "swap".equals(regime)) {
                context.drawText(textRenderer, "换成", x + 180, 78, 0xFFAAAAAA, false);
            } else if ("market".equals(category) && "redeem".equals(regime)) {
                context.drawText(textRenderer, "兑成", x + 180, 78, 0xFFAAAAAA, false);
            } else if ("issuance".equals(category) && !data.has("personal")) {
                context.drawText(textRenderer, "名字", x + 180, 78, 0xFFAAAAAA, false);
            }
        }
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
            case "exchange" -> step == 0 ? "兑换 · 付出" : "兑换 · 得到";
            case "checks" -> "汇票";
            case "market" -> step == 0 ? "交易 · 选择银行券" : "交易 · 方式";
            case "issuance" -> data.has("personal") ? "个人发行" : "个人发行 · 汇率";
            case "management" -> "管理";
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
            rows.add(new ChoiceListWidget.Choice("checks", "汇票"));
            rows.add(new ChoiceListWidget.Choice("market", "交易"));
            rows.add(new ChoiceListWidget.Choice("issuance", "个人发行"));
            if (data.has("admin") && data.get("admin").getAsBoolean()) {
                rows.add(new ChoiceListWidget.Choice("management", "管理"));
            }
            return rows;
        }
        if ("deposit".equals(category) && step == 0) {
            return List.of(
                    new ChoiceListWidget.Choice("chest", "打开箱子存入"),
                    new ChoiceListWidget.Choice("transfer", "划拨存入"));
        }
        if ("withdraw".equals(category) && step == 0) {
            return List.of(
                    new ChoiceListWidget.Choice("chest", "打开箱子取出"),
                    new ChoiceListWidget.Choice("transfer", "划拨取出"));
        }
        if ("market".equals(category) && step == 1) {
            return List.of(
                    new ChoiceListWidget.Choice("buy", "买入"),
                    new ChoiceListWidget.Choice("sell", "卖出"),
                    new ChoiceListWidget.Choice("swap", "互换"),
                    new ChoiceListWidget.Choice("redeem", "通兑"));
        }
        if ("issuance".equals(category) && !data.has("personal")) {
            return List.of(
                    new ChoiceListWidget.Choice("float", "浮动"),
                    new ChoiceListWidget.Choice("peg", "钉住"),
                    new ChoiceListWidget.Choice("hard", "定额兑付"));
        }
        if ("issuance".equals(category)) {
            return List.of(
                    new ChoiceListWidget.Choice("issue", "按准备金发行"),
                    new ChoiceListWidget.Choice("unbacked", "虚发"),
                    new ChoiceListWidget.Choice("reserve-deposit", "存入央行准备金"),
                    new ChoiceListWidget.Choice("reserve-withdraw", "取出央行准备金"));
        }
        if ("management".equals(category)) {
            return List.of(
                    new ChoiceListWidget.Choice("freeze", "暂停或恢复兑付"),
                    new ChoiceListWidget.Choice("unbacked", "无准备金增发"),
                    new ChoiceListWidget.Choice("retire", "注销"));
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
            if ("chest".equals(picked.id())) {
                BankUiClient.send("chest " + category);
                close();
                return;
            }
            if ("transfer".equals(picked.id())) {
                step = 1;
                clearAndInit();
                return;
            }
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

    private void submit() {
        String qty = amount == null || amount.getText().isBlank() ? "1" : amount.getText().trim();
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
            case "redeem" -> "通兑";
            default -> "买入";
        };
    }

    private String issuanceVerb() {
        return switch (regime) {
            case "unbacked" -> "虚发";
            case "reserve-deposit" -> "划入准备金";
            case "reserve-withdraw" -> "划出准备金";
            default -> "发行";
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
