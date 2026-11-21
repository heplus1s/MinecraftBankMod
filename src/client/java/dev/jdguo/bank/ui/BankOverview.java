package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** One-page overview projection and measured layout. Never changes or recomputes ledger balances. */
public final class BankOverview {
    private BankOverview() {}
    public record Cell(int x, int y, int width, int height, List<String> lines,
                       int rightFrom, boolean heading, String help) {}
    public record Layout(int width, int height, float scale, List<Cell> cells) {
        public boolean readable() { return scale >= .55f; }
    }
    public static final String SCOPE = "仅统计已登记账本及有效纸币；未扫描全世界实物。M1 包含 M0；M2 按现行价格折算，三项不可相加。";

    public static String exact(String value) {
        if (value == null || value.isBlank()) return "暂无数据";
        try { return new BigDecimal(value.replace(",", "")).stripTrailingZeros().toPlainString(); }
        catch (NumberFormatException invalid) { return value; }
    }
    static String field(JsonObject data, String currency, String key) {
        if (data.has("central")) for (var item : data.getAsJsonArray("central")) {
            var row = item.getAsJsonObject();
            if (!currency.equals(BankSnapshot.text(row, "id"))) continue;
            String raw = BankSnapshot.text(row, key);
            // Older server snapshots already exposed physical custody as vault.
            if (raw == null && "custody".equals(key) && !"note".equals(currency)) raw = BankSnapshot.text(row,"vault");
            return exact(raw);
        }
        return "暂无数据";
    }
    static String quote(JsonObject data, String exactKey, String fallback) {
        String raw = BankSnapshot.text(data, exactKey);
        if (raw == null) raw = BankSnapshot.text(data, fallback);
        return raw == null ? "暂无牌价" : exact(raw);
    }
    /** Split without abbreviating or discarding any character, including long exact amounts. */
    static List<String> wrap(String value, int width, ToIntFunction<String> measure) {
        List<String> lines = new ArrayList<>();
        String remaining = value == null ? "暂无数据" : value;
        while (!remaining.isEmpty()) {
            int end = 0;
            while (end < remaining.length()) {
                int next = remaining.offsetByCodePoints(end, 1);
                if (end > 0 && measure.applyAsInt(remaining.substring(0, next)) > width) break;
                end = next;
            }
            lines.add(remaining.substring(0, end)); remaining = remaining.substring(end);
        }
        return lines.isEmpty() ? List.of("") : List.copyOf(lines);
    }
    public static Layout layout(JsonObject data, int availableWidth, int availableHeight, ToIntFunction<String> measure) {
        int w = Math.max(320, availableWidth), y = 0;
        List<Cell> cells = new ArrayList<>();
        String note = BankRates.centralName(data);
        var unit = wrap("货币统计（单位：" + note + "）", w - 12, measure);
        int unitHeight = unit.size() * 12 + 4;
        cells.add(new Cell(0, y, w, unitHeight, unit, Integer.MAX_VALUE, true, SCOPE));
        y += unitHeight + 2;
        String[] titles = {"M0 · 银行外流通", "M1 · 狭义货币", "M2 · 折算广义货币"};
        String[] keys = {"m0", "m1", "m2"};
        int cardW = (w - 8) / 3, cardH = 0;
        List<List<String>> cards = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            List<String> lines = new ArrayList<>(wrap(titles[i], cardW - 12, measure));
            lines.addAll(wrap(exact(BankSnapshot.text(data, keys[i])), cardW - 12, measure));
            cards.add(lines); cardH = Math.max(cardH, lines.size() * 12 + 8);
        }
        for (int i = 0; i < 3; i++) cells.add(new Cell(i * (cardW + 4), y, cardW, cardH, cards.get(i),
                wrap(titles[i], cardW - 12, measure).size(), true, "单位：" + note + "。" + SCOPE));
        y += cardH + 6;
        int labelW = 88, valueW = (w - labelW) / 3;
        String[][] table = {
                {"账户（原币）", "铜锭", "钻石", note},
                {"实物准备金", field(data,"copper","reserve"), field(data,"diamond","reserve"), "—"},
                {"央行自有储蓄", field(data,"copper","treasury"), field(data,"diamond","treasury"), field(data,"note","treasury")},
                {"存款保管池", field(data,"copper","custody"), field(data,"diamond","custody"), field(data,"note","custody")}
        };
        String[] help = {"各列保持原币种数量，不合计不同币种。", "铜锭和钻石是实物准备金，不是被发行的货币；央行券的抵押物看这两列。",
                "央行独立持有的储蓄，不含准备金和代保管的存款。", "替玩家和系统账户保管的存款，不是央行可自由拨用的自有资产。"};
        for (int row = 0; row < table.length; row++) {
            List<List<String>> texts = new ArrayList<>(); int rowH = 0;
            for (int col = 0; col < 4; col++) {
                var lines = wrap(table[row][col], (col == 0 ? labelW : valueW) - 12, measure);
                texts.add(lines); rowH = Math.max(rowH, lines.size() * 12 + 8);
            }
            int x = 0;
            for (int col = 0; col < 4; col++) {
                int cw = col == 0 ? labelW : valueW;
                cells.add(new Cell(x, y, cw - 1, rowH - 1, texts.get(col), row > 0 && col > 0 ? 0 : Integer.MAX_VALUE, row == 0, help[row]));
                x += cw;
            }
            y += rowH;
        }
        y += 6;
        if (data.has("outstandingBillTotal")) {
            String outstanding = "待兑汇票 " + exact(BankSnapshot.text(data,"outstandingBillTotal")) + " " + note
                    + "（" + BankSnapshot.text(data,"outstandingBillCount") + " 张）；其中已确认系统签发 "
                    + exact(BankSnapshot.text(data,"systemBillTotal")) + "（" + BankSnapshot.text(data,"systemBillCount") + " 张）";
            var lines = wrap(outstanding, w - 12, measure); int h = lines.size() * 12 + 6;
            cells.add(new Cell(0,y,w,h,lines,Integer.MAX_VALUE,false,
                    "已计入 M1，不计入 M0；缺少签发来源的旧票只计入待兑总额。")); y += h;
        }
        String[] quotes = {"1 钻石 = " + quote(data,"copperPerDiamondExact","copperPerDiamond") + " 铜锭",
                "1 " + note + " = " + quote(data,"diamondsPerNoteExact","diamondsPerNote") + " 钻石"};
        for (String value : quotes) {
            var lines = wrap(value, w - 12, measure); int h = lines.size() * 12 + 6;
            cells.add(new Cell(0,y,w,h,lines,Integer.MAX_VALUE,false,"服务端当前牌价；兑换差价另计，实际扣款以交易预览为准。")); y += h;
        }
        var foot = wrap("存款保管 ≠ 自有储蓄；M1 含 M0，M2 为折算展示", w - 12, measure);
        int h = foot.size() * 12 + 4;
        cells.add(new Cell(0,y,w,h,foot,Integer.MAX_VALUE,false,SCOPE)); y += h;
        float scale = Math.min(1f, Math.min((float)availableWidth / w, (float)availableHeight / y));
        return new Layout(w,y,scale,List.copyOf(cells));
    }
}
