package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Custom counter. Numbers come from the server snapshot; buttons only send action tokens. */
public final class BankTerminalScreen extends Screen {
    private JsonObject data;
    private TextFieldWidget amount;
    private TextFieldWidget name;
    private TextFieldWidget extra;
    private String regime = "float";
    private String currency = "diamond";

    public BankTerminalScreen(JsonObject data) {
        super(Text.literal("Central Bank"));
        this.data = data;
    }

    void show(JsonObject data) {
        this.data = data;
        clearAndInit();
    }

    @Override
    protected void init() {
        int x = this.width / 2 - 180;
        int y = this.height / 2 - 110;
        amount = new TextFieldWidget(textRenderer, x + 200, y + 78, 70, 16, Text.literal("amount"));
        name = new TextFieldWidget(textRenderer, x + 274, y + 78, 70, 16, Text.literal("name"));
        extra = new TextFieldWidget(textRenderer, x + 200, y + 98, 144, 16, Text.literal("extra"));
        amount.setText("1");
        extra.setText("diamond");
        addDrawableChild(amount);
        addDrawableChild(name);
        addDrawableChild(extra);
        int row = 0;
        nav(x, y + row++ * 18, "储蓄", "page savings");
        nav(x, y + row++ * 18, "存入", "page deposit");
        nav(x, y + row++ * 18, "取出", "page withdraw");
        nav(x, y + row++ * 18, "兑换", "page exchange");
        nav(x, y + row++ * 18, "汇票", "page checks");
        nav(x, y + row++ * 18, "交易", "page market");
        nav(x, y + row++ * 18, "发行", "page issuance");
        if (data.has("admin") && data.get("admin").getAsBoolean()) {
            nav(x, y + row++ * 18, "管理", "page management");
        }
        nav(x, y + row * 18, "关闭", "close");
        addDrawableChild(ButtonWidget.builder(Text.literal("执行"), button -> act()).dimensions(x + 200, y + 122, 70, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("虚发"), b -> BankUiClient.send("note-unbacked " + qty())).dimensions(x + 274, y + 122, 70, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("卖出"), b -> BankUiClient.send("note-sell " + name.getText().trim() + " " + qty())).dimensions(x + 90, y + 172, 50, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("通兑"), b -> BankUiClient.send("note-redeem " + name.getText().trim() + " " + extra.getText().trim() + " " + qty())).dimensions(x + 144, y + 172, 50, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("存准备"), b -> BankUiClient.send("reserve-deposit " + qty())).dimensions(x + 198, y + 172, 50, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("取准备"), b -> BankUiClient.send("reserve-withdraw " + qty())).dimensions(x + 252, y + 172, 50, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("宣告破产"), b -> BankUiClient.send("note-bankrupt")).dimensions(x + 306, y + 172, 60, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("浮动"), b -> regime = "float").dimensions(x + 90, y + 150, 40, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("钉住"), b -> regime = "peg").dimensions(x + 132, y + 150, 40, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("定额"), b -> regime = "hard").dimensions(x + 174, y + 150, 40, 16).build());
        if (data.has("confirm") && data.get("confirm").getAsBoolean()) {
            addDrawableChild(ButtonWidget.builder(Text.literal("返回"), b -> BankUiClient.send("note-bankrupt-no")).dimensions(x + 90, y + 194, 80, 16).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("确认宣告破产"), b -> BankUiClient.send("note-bankrupt-yes")).dimensions(x + 260, y + 194, 100, 16).build());
        }
    }

    private void nav(int x, int y, String label, String action) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> {
            if ("close".equals(action)) {
                BankUiClient.send("close");
                close();
            } else {
                BankUiClient.send(action);
            }
        }).dimensions(x, y, 70, 16).build());
    }

    private String qty() {
        return amount.getText().isBlank() ? "1" : amount.getText().trim();
    }

    private void act() {
        String page = data.has("page") ? data.get("page").getAsString() : "savings";
        String qty = qty();
        String who = name.getText().trim();
        String anchor = extra.getText().isBlank() ? currency : extra.getText().trim();
        switch (page) {
            case "deposit" -> BankUiClient.send("deposit-held");
            case "withdraw" -> BankUiClient.send("withdraw " + anchor + " " + qty);
            case "exchange" -> BankUiClient.send("exchange " + currency + " " + anchor + " " + qty);
            case "checks" -> BankUiClient.send("check " + anchor + " " + qty + " bearer");
            case "market" -> BankUiClient.send("note-buy " + who + " " + qty);
            case "issuance" -> {
                if (!who.isBlank()) {
                    BankUiClient.send("note-create " + regime + " " + anchor + " " + who + " " + qty);
                } else {
                    BankUiClient.send("note-issue " + qty);
                }
            }
            case "management" -> BankUiClient.send("admin-unbacked note " + qty);
            default -> {
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        int x = this.width / 2 - 80;
        int y = this.height / 2 - 108;
        context.drawText(textRenderer, "M0 " + text("m0") + "  M1 " + text("m1") + "  M2 " + text("m2"), x, y, 0xFFD0B060, false);
        context.drawText(textRenderer, "铜 " + text("copper") + "  钻 " + text("diamond") + "  银行券 " + text("note"), x, y + 12, 0xFFE0E0E0, false);
        context.drawText(textRenderer, "储蓄总池 " + text("savings") + "  发行准备金 " + text("issuanceDiamond"), x, y + 24, 0xFFB0C0B0, false);
        if (data.has("personal")) {
            context.drawText(textRenderer, data.get("personal").getAsString() + " " + text("regime")
                    + " 流通 " + text("circulation") + " 准备金 " + text("reserve") + "/" + text("required"), x, y + 40, 0xFFE8D090, false);
        }
        context.drawText(textRenderer, "数量 / 名字 / 锚或币种", x, y + 64, 0xFF888888, false);
        if (data.has("confirm") && data.get("confirm").getAsBoolean()) {
            context.drawText(textRenderer, "确认后才会宣告破产", x, y + 160, 0xFFFF6666, false);
        }
    }

    private String text(String key) {
        return data.has(key) ? data.get(key).getAsString() : "-";
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
