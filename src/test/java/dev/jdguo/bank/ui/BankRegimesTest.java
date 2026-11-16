package dev.jdguo.bank.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankRegimesTest {
    @Test
    void missingSwitchDataDoesNotInventAButton() {
        JsonObject data = new JsonObject();
        data.addProperty("regimeName", "浮动汇率制");

        assertFalse(BankRegimes.switchesKnown(data));
        assertTrue(BankRegimes.offers(data).isEmpty());
        assertTrue(BankRegimes.figures(data).contains("当前发行制度：浮动汇率制"));
        assertTrue(BankRegimes.figures(data).contains("现价：暂无牌价"));
        assertTrue(String.join("\n", BankRegimes.rules()).contains("固定汇率制"));
        assertTrue(BankRegimes.HARD_RULE.contains("面值并不改变"));
    }

    @Test
    void allowedOffersBecomeSwitchCommandsAndRefusalsDoNot() {
        JsonObject data = new JsonObject();
        JsonObject note = new JsonObject();
        note.addProperty("regime", "FLOAT");
        note.addProperty("regimeName", "浮动汇率制");
        note.addProperty("live", "0.000000");
        JsonArray switches = new JsonArray();
        switches.add(offer("peg", "note", "联系汇率制，锚为银行券", true, null));
        switches.add(offer("hard", "diamond", "固定汇率制，锚为钻石", false, "现价已经偏离面值"));
        switches.add(offer("hard", "note", "固定汇率制，锚为银行券", true, null));
        note.add("switches", switches);
        data.add("ownNote", note);

        assertTrue(BankRegimes.switchesKnown(data));
        List<BankRegimes.Offer> offers = BankRegimes.offers(data);
        assertEquals("note-switch peg note", offers.get(0).command());
        assertEquals("可以切换为联系汇率制，锚为银行券", offers.get(0).button());
        assertNull(offers.get(1).button());
        assertEquals("现价已经偏离面值", offers.get(1).refusal());
        assertNull(offers.get(2).command());
        assertTrue(BankRegimes.figures(data).contains("现价：0"));
    }

    @Test
    void issuanceReserveUsesNewFieldsAndDoesNotInventANumber() {
        JsonObject data = new JsonObject();
        JsonObject note = new JsonObject();
        note.addProperty("regimeName", "浮动汇率制");
        note.addProperty("anchor", "diamond");
        note.addProperty("issuanceReserve", "2001");
        note.addProperty("reserveCurrencyName", "钻石");
        note.addProperty("locked", "10");
        data.add("ownNote", note);

        List<String> lines = BankRegimes.issuanceHoldings(data);
        assertTrue(lines.contains("发行准备金：钻石 2001"));
        assertTrue(lines.contains("已锁定：10"));
        assertFalse(String.join("\n", lines).contains("央行银行券准备金"));

        JsonObject peg = new JsonObject();
        JsonObject pegNote = new JsonObject();
        pegNote.addProperty("regimeName", "联系汇率制");
        pegNote.addProperty("anchor", "note");
        pegNote.addProperty("centralReserve", "40");
        peg.add("ownNote", pegNote);
        List<String> pegLines = BankRegimes.issuanceHoldings(peg);
        assertTrue(pegLines.contains("央行银行券准备金：40"));
        assertTrue(pegLines.get(0).contains("暂无数据"));

        JsonObject named = new JsonObject();
        JsonObject namedNote = new JsonObject();
        namedNote.addProperty("reserveCurrency", "diamond");
        namedNote.addProperty("issuanceReserve", "12.5");
        namedNote.addProperty("issuanceLocked", "2");
        namedNote.addProperty("issuanceFree", "10.5");
        named.add("ownNote", namedNote);
        List<String> namedLines = BankRegimes.issuanceHoldings(named);
        assertTrue(namedLines.contains("发行准备金：钻石 12.5000"));
        assertTrue(namedLines.contains("已锁定：2"));
        assertTrue(namedLines.contains("可动用：10.5000"));

        List<BankRegimes.AnchorRow> defaults = BankRegimes.anchorRows(new JsonObject());
        assertEquals(List.of("实物货币", "铜锭", "钻石", "发行货币", "银行券"),
                defaults.stream().map(BankRegimes.AnchorRow::label).toList());
        assertTrue(defaults.get(0).group());
        assertFalse(defaults.get(1).group());
        assertEquals("note", defaults.get(4).id());
    }

    @Test
    void bankruptcyBlocksSwitching() {
        JsonObject data = new JsonObject();
        data.addProperty("bankrupt", true);
        data.add("switches", new JsonArray());

        assertEquals("这张个人银行券已经破产，不能切换发行制度。", BankRegimes.blockReason(data));
    }

    private static JsonObject offer(String regime, String anchor, String name, boolean allowed, String reason) {
        JsonObject row = new JsonObject();
        row.addProperty("regime", regime);
        row.addProperty("anchor", anchor);
        row.addProperty("name", name);
        row.addProperty("allowed", allowed);
        if (reason != null) {
            row.addProperty("reason", reason);
        }
        return row;
    }
}
