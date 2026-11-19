package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Separate gift workflow: periodic snapshots never steal keyboard focus or replace an edited draft. */
public final class WelcomeGiftScreen extends Screen {
    private JsonObject data;
    private final Screen parent;
    private final boolean administrator;
    private String currency, amountDraft, searchDraft = "";
    private long draftRevision;
    private boolean enabled, dirty, picking, awaiting, loading;
    private int scroll;
    private TextFieldWidget amount, search;
    private ButtonWidget submit, currencyButton, enabledButton;
    private record Currency(String id, String name) {}

    WelcomeGiftScreen(JsonObject data, Screen parent) {
        super(Text.literal(BankSnapshot.admin(data) ? "新人礼包设置" : "领取新人礼包"));
        this.data = data; this.parent = parent; administrator = BankSnapshot.admin(data); loadDraft();
    }
    boolean admin() { return administrator; }
    private JsonObject gift() { return data.getAsJsonObject("welcomeGift"); }
    private static String value(JsonObject row, String key) { String s = BankSnapshot.text(row, key); return s == null ? "" : s; }
    private long revision() { return gift().get("revision").getAsLong(); }
    private boolean stale() { return draftRevision != revision(); }
    private void loadDraft() {
        currency = value(gift(), "currency"); amountDraft = value(gift(), "amount");
        enabled = gift().get("enabled").getAsBoolean(); draftRevision = revision(); dirty = false; awaiting = false;
    }
    void show(JsonObject next) {
        data = next; awaiting = false;
        if (administrator && stale() && currency.equals(value(gift(), "currency"))
                && sameAmount(amountDraft, value(gift(), "amount")) && enabled == gift().get("enabled").getAsBoolean()) dirty = false;
        if (!dirty && !picking) {
            loadDraft();
            if (amount != null && !amount.isFocused()) {
                loading = true; amount.setText(amountDraft); loading = false;
            }
        }
        updateControls();
    }
    private int bodyWidth() { return Math.min(460, width - 24); }
    private int left() { return (width - bodyWidth()) / 2; }
    private boolean small() { return width < 260 || height < 240; }
    private ButtonWidget button(String text, int x, int y, int w, Runnable action) {
        return addDrawableChild(ButtonWidget.builder(Text.literal(textRenderer.trimToWidth(text, w - 10)), b -> action.run())
                .dimensions(x, y, w, 20).build());
    }
    @Override protected void init() {
        amount = null; search = null; submit = null; currencyButton = null; enabledButton = null;
        int x = left(), w = bodyWidth();
        button("返回", x + w - 54, 8, 54, this::close);
        if (small()) return;
        if (picking) {
            search = new TextFieldWidget(textRenderer, x, 42, w, 20, Text.literal("搜索币种名称或 ID"));
            search.setMaxLength(80); search.setText(searchDraft);
            search.setChangedListener(s -> { searchDraft = s; scroll = 0; }); addDrawableChild(search);
            button("返回设置", x, height - 30, w, () -> { picking = false; scroll = 0; clearAndInit(); });
            return;
        }
        if (administrator) {
            button("重新载入设置", x, 35, 110, () -> { loadDraft(); scroll = 0; clearAndInit(); });
            currencyButton = button("币种：" + currencyName(), x, 64, w, () -> {
                remember(); picking = true; scroll = 0; clearAndInit();
            });
            amount = new TextFieldWidget(textRenderer, x, 106, w, 20, Text.literal("每人领取数量"));
            amount.setMaxLength(29); amount.setText(amountDraft);
            amount.setChangedListener(s -> { amountDraft = s; if (!loading) dirty = true; updateControls(); }); addDrawableChild(amount);
            enabledButton = button("", x, 134, w, () -> { enabled = !enabled; dirty = true; updateControls(); });
            submit = button("确认保存", x, height - 30, w, () -> {
                remember(); if (!validAmount() || stale() || awaiting) return;
                awaiting = true;
                BankUiClient.send("admin-welcome-set " + draftRevision + " " + enabled + " " + amountDraft.trim() + " " + currency);
                // Server is authoritative; keep a stale draft intact until explicitly reloaded.
                updateControls();
            });
        } else {
            submit = button("领取到银行储蓄", x, height - 30, w, () -> {
                if (awaiting || gift().get("claimed").getAsBoolean() || !gift().get("enabled").getAsBoolean()) return;
                awaiting = true; BankUiClient.send("welcome-claim " + revision()); updateControls();
            });
        }
        updateControls();
    }
    private void remember() { if (amount != null) amountDraft = amount.getText(); if (search != null) searchDraft = search.getText(); }
    private List<Currency> currencies() {
        List<Currency> result = new ArrayList<>();
        if (gift().has("currencies")) for (var item : gift().getAsJsonArray("currencies")) {
            var row = item.getAsJsonObject(); result.add(new Currency(value(row,"id"), value(row,"name")));
        }
        return result;
    }
    private List<Currency> filtered() {
        String query = searchDraft.toLowerCase(Locale.ROOT).trim();
        return currencies().stream().filter(c -> c.name.toLowerCase(Locale.ROOT).contains(query) || c.id.toLowerCase(Locale.ROOT).contains(query)).toList();
    }
    private String currencyName() {
        for (Currency c : currencies()) if (c.id.equals(currency)) return c.name;
        return currency.equals(value(gift(), "currency")) ? value(gift(), "currencyName") : currency;
    }
    private boolean validAmount() {
        try {
            String text = amountDraft.trim();
            if (!text.matches("[0-9]{1,24}(\\.[0-9]{1,4})?")) return false;
            BigDecimal n = new BigDecimal(text);
            return n.signum() > 0 && (!(currency.equals("copper") || currency.equals("diamond")) || n.stripTrailingZeros().scale() <= 0);
        } catch (RuntimeException invalid) { return false; }
    }
    private static boolean sameAmount(String a, String b) {
        try { return new BigDecimal(a.trim()).compareTo(new BigDecimal(b.trim())) == 0; }
        catch (RuntimeException invalid) { return false; }
    }
    private void updateControls() {
        if (currencyButton != null) currencyButton.setMessage(Text.literal(textRenderer.trimToWidth("币种：" + currencyName(), bodyWidth() - 10)));
        if (enabledButton != null) enabledButton.setMessage(Text.literal(enabled ? "领取功能：启用" : "领取功能：停用"));
        if (submit == null) return;
        if (administrator) submit.active = !awaiting && !stale() && validAmount() && !currency.isBlank();
        else {
            boolean claimed = gift().get("claimed").getAsBoolean();
            submit.setMessage(Text.literal(claimed ? "已领取" : !gift().get("enabled").getAsBoolean() ? "礼包暂未开启" : awaiting ? "正在领取…" : "领取到银行储蓄"));
            submit.active = !claimed && gift().get("enabled").getAsBoolean() && !awaiting;
        }
    }
    private List<String> information() {
        List<String> result = new ArrayList<>();
        if (administrator) {
            if (stale()) result.add("设置已更新，请重新载入后编辑。当前草稿未提交。");
            result.add("当前生效：" + (gift().get("enabled").getAsBoolean() ? "启用" : "停用") + " · " + value(gift(), "amount") + " " + value(gift(), "currencyName"));
            result.add("旧玩家、新玩家均可领一次；修改设置不重置领取记录。资金只来自央行自有储蓄，不足时不发放。");
            result.add("铜锭、钻石必须整数；央行券和个人币最多四位小数。个人币由央行现场认购，需可用牌价及挂牌库存。");
        } else {
            result.add(gift().get("claimed").getAsBoolean() ? "你已领取过本服务器的新人礼包。" : "新老玩家均可领取一次，直接存入银行储蓄。");
            result.add("礼包币种：" + value(gift(), "currencyName"));
            result.add("领取数量：" + value(gift(), "amount"));
            result.add("资金不足或发放失败不会消耗领取资格。重连、修改礼包设置不会重新获得资格。");
        }
        String refusal = BankSnapshot.text(data, "refusal"); if (refusal != null && !refusal.isBlank()) result.add(refusal);
        return result;
    }
    private int contentTop() { return picking ? 70 : administrator ? 164 : 44; }
    private int contentBottom() { return height - 42; }
    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx); int x = left(), w = bodyWidth();
        ctx.drawText(textRenderer, textRenderer.trimToWidth(title.getString(), w - 62), x, 14, 0xFFE8D090, false);
        if (small()) {
            ctx.drawText(textRenderer, "请放大窗口或降低 GUI 缩放", x, 48, 0xFFFFFFFF, false);
            super.render(ctx,mx,my,delta); return;
        }
        if (administrator && !picking) ctx.drawText(textRenderer, "每人领取数量", x, 93, 0xFFE8D090, false);
        int top = contentTop(), bottom = contentBottom();
        ctx.fill(x,top,x+w,bottom,0xDD202A34);
        List<String> lines = new ArrayList<>();
        if (!picking) for (String line : information()) { lines.addAll(BankOverview.wrap(line,w-16,textRenderer::getWidth)); lines.add(""); }
        int total = picking ? filtered().size()*27 : lines.size()*12;
        scroll = Math.max(0,Math.min(scroll,Math.max(0,total-(bottom-top-8))));
        ctx.enableScissor(x,top,x+w,bottom);
        if (picking) {
            var rows = filtered();
            for (int i=0;i<rows.size();i++) {
                Currency c=rows.get(i); int y=top+i*27-scroll;
                if (mx>=x && mx<x+w && my>=Math.max(top,y) && my<Math.min(bottom,y+26)) ctx.fill(x,y,x+w,y+26,0xFF465466);
                String label=(c.id.equals(currency)?"✓ ":"")+c.name;
                ctx.drawText(textRenderer,textRenderer.trimToWidth(label,w-12),x+6,y+4,0xFFFFFFFF,false);
                ctx.drawText(textRenderer,textRenderer.trimToWidth(c.id,w-12),x+6,y+15,0xFFAAAAAA,false);
            }
            if(rows.isEmpty())ctx.drawText(textRenderer,"没有匹配币种",x+6,top+6,0xFFAAAAAA,false);
        } else for(int i=0;i<lines.size();i++)ctx.drawText(textRenderer,lines.get(i),x+8,top+4+i*12-scroll,0xFFFFFFFF,false);
        ctx.disableScissor();
        super.render(ctx,mx,my,delta);
        if(total>bottom-top-8)ctx.drawText(textRenderer,"滚轮查看",x+w-48,bottom+2,0xFFAAAAAA,false);
        if(currencyButton!=null && mx>=x && mx<x+w && my>=64 && my<84)
            ctx.drawTooltip(textRenderer,BankOverview.wrap(currencyName(),Math.min(300,w),textRenderer::getWidth).stream().<Text>map(Text::literal).toList(),mx,my);
        if(picking && mx>=x && mx<x+w && my>=top && my<bottom) {
            int index=(my-top+scroll)/27; var rows=filtered();
            if(index>=0 && index<rows.size())ctx.drawTooltip(textRenderer,BankOverview.wrap(rows.get(index).name+" ("+rows.get(index).id+")",Math.min(300,w),textRenderer::getWidth).stream().<Text>map(Text::literal).toList(),mx,my);
        }
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        if(picking && button==0 && x>=left() && x<left()+bodyWidth() && y>=contentTop() && y<contentBottom()) {
            int index=(int)(y-contentTop()+scroll)/27; var rows=filtered();
            if(index>=0 && index<rows.size()) { currency=rows.get(index).id; dirty=true; picking=false; scroll=0; clearAndInit(); }
            return true;
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double amount) {
        if(x>=left() && x<left()+bodyWidth() && y>=contentTop() && y<contentBottom()) { scroll=Math.max(0,scroll-(int)Math.signum(amount)*24); return true; }
        return super.mouseScrolled(x,y,amount);
    }
    @Override public void tick() { if(amount!=null)amount.tick(); if(search!=null)search.tick(); }
    @Override public void resize(net.minecraft.client.MinecraftClient client,int width,int height) { remember(); super.resize(client,width,height); }
    @Override public void close() {
        if(picking) { picking=false; scroll=0; clearAndInit(); return; }
        if(parent instanceof BankAdminScreen screen)screen.show(data);
        if(parent instanceof BankTerminalScreen screen)screen.show(data);
        client.setScreen(parent);
    }
    @Override public boolean shouldPause() { return false; }
}
