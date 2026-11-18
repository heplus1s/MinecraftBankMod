package dev.jdguo.bank.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Read-only admin directory. Fixed column geometry, horizontal pan and a scrollable detail overlay. */
public final class BankPlayerListScreen extends Screen {
    private static final int[] WIDTHS = {120, 126, 100, 100, 110, 70, 120, 110, 126};
    private static final String[] HEADERS = {"玩家", "央行券余额", "钻石储蓄", "铜锭储蓄", "个人币存款", "是否发币", "个人币名称", "准备金类型", "发行数额"};
    private static final int TOP = 66, ROW = 25;
    private JsonObject data;
    private TextFieldWidget search;
    private String draft = "";
    private int horizontal, vertical, detailScroll;
    private JsonArray details;
    private String detailTitle;
    private String detailPlayer;
    private boolean issuedDetails;
    private String hover;

    BankPlayerListScreen(JsonObject data) { super(Text.literal("玩家存款管理")); this.data = data; draft = text(list(), "query"); }
    private JsonObject list() { return data.getAsJsonObject("adminPlayers"); }
    private static String text(JsonObject o, String key) { return o.has(key) ? o.get(key).getAsString() : ""; }
    private int number(String key) { return list().get(key).getAsInt(); }
    void show(JsonObject next) {
        boolean samePage = list().get("page").equals(next.getAsJsonObject("adminPlayers").get("page"))
                && text(list(), "query").equals(text(next.getAsJsonObject("adminPlayers"), "query"));
        data = next;
        if (!samePage) { details = null; vertical = 0; }
        else if (details != null) {
            JsonObject current = null;
            for (var item : list().getAsJsonArray("rows")) {
                var row = item.getAsJsonObject();
                if (text(row, "id").equals(detailPlayer)) { current = row; break; }
            }
            if (current == null) details = null;
            else details = current.getAsJsonArray(issuedDetails ? "issued" : "holdings");
        }
        // Do not rebuild widgets: periodic snapshots must not steal focus, selection or drafts.
    }
    private void button(String name, int x, int y, int w, Runnable action) {
        addDrawableChild(ButtonWidget.builder(Text.literal(name), b -> action.run()).dimensions(x, y, w, 20).build());
    }
    private void request(int page) { draft = search.getText().trim(); BankUiClient.send("admin-players " + page + (draft.isEmpty() ? "" : " " + draft)); }
    @Override protected void init() {
        if (width < 260 || height < 200) { button("返回", 10, height - 28, 60, this::close); return; }
        search = new TextFieldWidget(textRenderer, 10, 32, width - 152, 20, Text.literal("搜索玩家名或 UUID"));
        search.setMaxLength(64); search.setText(draft); addDrawableChild(search);
        button("搜索", width - 136, 32, 58, () -> request(0));
        button("刷新", width - 72, 32, 62, () -> request(number("page")));
        button("返回", width - 62, 6, 52, this::close);
        button("上一页", 10, height - 27, 58, () -> request(number("page") - 1));
        button("下一页", 74, height - 27, 58, () -> request(number("page") + 1));
        button("←", width - 74, height - 27, 30, () -> horizontal = Math.max(0, horizontal - 180));
        button("→", width - 40, height - 27, 30, () -> horizontal = Math.min(maxHorizontal(), horizontal + 180));
    }
    private int maxHorizontal() { return Math.max(0, java.util.Arrays.stream(WIDTHS).sum() - (width - 20)); }
    private int bodyBottom() { return height - 48; }
    @Override public void tick() { if (search != null) search.tick(); }
    @Override public void resize(net.minecraft.client.MinecraftClient client, int width, int height) {
        if (search != null) draft = search.getText(); super.resize(client, width, height);
    }
    private void cell(DrawContext ctx, String value, int x, int y, int w, boolean numeric, int color, int mx, int my) {
        String display = textRenderer.trimToWidth(value, w - 12);
        if (!display.equals(value)) display = textRenderer.trimToWidth(value, w - 22) + "…";
        int tx = numeric ? x + w - 6 - textRenderer.getWidth(display) : x + 6;
        ctx.drawText(textRenderer, display, tx, y + 8, color, false);
        if (mx >= Math.max(10, x) && mx < Math.min(width - 10, x + w) && my >= y && my < y + ROW) hover = value;
    }
    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx); hover = null;
        ctx.drawText(textRenderer, "玩家存款管理", 10, 12, 0xFFE8D090, false);
        if (width < 260 || height < 200) {
            ctx.drawText(textRenderer, "请放大窗口或降低 GUI 缩放", 10, 50, 0xFFFFFFFF, false);
            super.render(ctx, mx, my, delta); return;
        }
        horizontal = Math.max(0, Math.min(horizontal, maxHorizontal()));
        JsonArray rows = list().getAsJsonArray("rows");
        vertical = Math.max(0, Math.min(vertical, Math.max(0, rows.size() * ROW - (bodyBottom() - TOP - ROW))));
        ctx.enableScissor(10, TOP, width - 10, bodyBottom());
        int x = 10 - horizontal;
        for (int col = 0; col < WIDTHS.length; col++) {
            ctx.fill(x, TOP, x + WIDTHS[col] - 1, TOP + ROW, 0xFF394454);
            String heading = col == 1 ? (data.has("centralCurrencyName") ? text(data, "centralCurrencyName") : "央行券") + "余额" : HEADERS[col];
            cell(ctx, heading, x, TOP, WIDTHS[col], false, 0xFFE8D090, mx, my); x += WIDTHS[col];
        }
        ctx.disableScissor();
        ctx.enableScissor(10, TOP + ROW, width - 10, bodyBottom());
        for (int i = 0; i < rows.size(); i++) {
            JsonObject row = rows.get(i).getAsJsonObject();
            JsonArray holdings = row.getAsJsonArray("holdings"), issues = row.getAsJsonArray("issued");
            JsonObject issue = issues.size() == 0 ? null : issues.get(0).getAsJsonObject();
            String[] values = {text(row,"name"), text(row,"note"), text(row,"diamond"), text(row,"copper"),
                    "查看 " + holdings.size() + " 种", issues.size() == 0 ? "否" : "是",
                    issue == null ? "—" : issues.size() > 1 ? "查看 " + issues.size() + " 种" : text(issue,"name"),
                    issue == null ? "—" : issues.size() > 1 ? "多种（点击）" : text(issue,"reserveType"),
                    issue == null ? "—" : issues.size() > 1 ? "点击查看" : text(issue,"amount")};
            x = 10 - horizontal; int y = TOP + ROW + i * ROW - vertical;
            for (int col = 0; col < WIDTHS.length; col++) {
                ctx.fill(x, y, x + WIDTHS[col] - 1, y + ROW - 1, i % 2 == 0 ? 0xDD202630 : 0xDD2A303B);
                cell(ctx, values[col], x, y, WIDTHS[col], col == 1 || col == 2 || col == 3 || col == 8,
                        col == 4 || col >= 6 && issue != null ? 0xFF88CFFF : 0xFFFFFFFF, mx, my);
                x += WIDTHS[col];
            }
        }
        ctx.disableScissor();
        if (rows.size() == 0) ctx.drawText(textRenderer, "没有匹配的玩家", 16, TOP + ROW + 8, 0xFFAAAAAA, false);
        String status = (number("page") + 1) + "/" + number("pages") + "  共 " + number("total") + " 人";
        boolean complete = !list().has("directoryComplete") || list().get("directoryComplete").getAsBoolean();
        if (!complete) status += " · 离线档案读取不完整";
        ctx.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(status, width - 20), width / 2, height - 42, complete ? 0xFFFFFFFF : 0xFFFFAA88);
        super.render(ctx, mx, my, delta);
        if (details != null) { hover = null; renderDetails(ctx, mx, my); }
        else if (hover != null && my >= TOP && my < bodyBottom()) ctx.drawTooltip(textRenderer, Text.literal(hover), mx, my);
    }
    private int panelW() { return Math.min(420, width - 30); }
    private int panelH() { return Math.min(240, height - 30); }
    private int panelX() { return (width - panelW()) / 2; }
    private int panelY() { return (height - panelH()) / 2; }
    private int detailRowHeight() { return issuedDetails ? 54 : 24; }
    private void renderDetails(DrawContext ctx, int mx, int my) {
        int x = panelX(), y = panelY(), w = panelW(), h = panelH();
        ctx.fill(0, 0, width, height, 0xA0000000); ctx.fill(x, y, x + w, y + h, 0xFF202A38);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(detailTitle, w - 20), x + 10, y + 10, 0xFFE8D090, false);
        ctx.fill(x + w - 62, y + h - 27, x + w - 8, y + h - 7, 0xFF465466);
        ctx.drawText(textRenderer, "关闭", x + w - 48, y + h - 21, 0xFFFFFFFF, false);
        detailScroll = Math.max(0, Math.min(detailScroll, Math.max(0, details.size() * detailRowHeight() - (h - 66))));
        ctx.enableScissor(x + 8, y + 30, x + w - 8, y + h - 34);
        for (int i = 0; i < details.size(); i++) {
            JsonObject row = details.get(i).getAsJsonObject(); int yy = y + 30 + i * detailRowHeight() - detailScroll;
            cell(ctx, text(row,"name"), x + 8, yy, w / 2 - 8, false, 0xFFFFFFFF, mx, my);
            cell(ctx, text(row,"amount"), x + w / 2, yy, w / 2 - 8, true, 0xFFFFFFFF, mx, my);
            if (issuedDetails) {
                String reserve = "准备金：" + text(row,"reserveType") + "  " + text(row,"reserveAmount");
                cell(ctx, reserve, x + 8, yy + 20, w - 16, false, 0xFFAAAAAA, mx, my);
                if (row.get("closed").getAsBoolean()) ctx.drawText(textRenderer, "已结业", x + 14, yy + 43, 0xFFFFAA88, false);
            }
        }
        if (details.size() == 0) ctx.drawText(textRenderer, issuedDetails ? "暂无个人发行货币" : "暂无个人币存款", x + 14, y + 38, 0xFFAAAAAA, false);
        ctx.disableScissor();
        if (hover != null && mx >= x + 8 && mx < x + w - 8 && my >= y + 30 && my < y + h - 34)
            ctx.drawTooltip(textRenderer, Text.literal(hover), mx, my);
    }
    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (details != null) {
            if (mx >= panelX() + panelW() - 62 && mx < panelX() + panelW() - 8 && my >= panelY() + panelH() - 27 && my < panelY() + panelH() - 7) details = null;
            return true;
        }
        if (button == 0 && mx >= 10 && mx < width - 10 && my >= TOP + ROW && my < bodyBottom()) {
            int index = (int)(my - TOP - ROW + vertical) / ROW;
            JsonArray rows = list().getAsJsonArray("rows");
            if (index >= 0 && index < rows.size()) {
                JsonObject row = rows.get(index).getAsJsonObject(); int xx = 10 - horizontal;
                for (int col = 0; col < WIDTHS.length; col++) {
                    if (mx >= xx && mx < xx + WIDTHS[col] && (col == 4 || col >= 6)) {
                        detailPlayer = text(row,"id"); issuedDetails = col != 4; details = row.getAsJsonArray(issuedDetails ? "issued" : "holdings");
                        detailTitle = text(row,"name") + (issuedDetails ? " · 已发行货币与准备金" : " · 个人币存款"); detailScroll = 0; return true;
                    }
                    xx += WIDTHS[col];
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }
    @Override public boolean mouseScrolled(double mx, double my, double amount) {
        int offset = (int)Math.signum(amount) * 24;
        if (details != null) detailScroll -= offset;
        else if (hasShiftDown()) horizontal = Math.max(0, Math.min(maxHorizontal(), horizontal - offset * 3));
        else vertical -= offset;
        return true;
    }
    @Override public boolean charTyped(char chr, int modifiers) {
        return details != null || super.charTyped(chr, modifiers);
    }
    @Override public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return details != null || super.mouseDragged(mx, my, button, dx, dy);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (details != null) { if (key == 256) details = null; return true; }
        if (key == 257 && search != null && search.isFocused()) { request(0); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void close() { BankUiClient.send("admin-players-close"); }
    @Override public boolean shouldPause() { return false; }
}
