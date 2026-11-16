package dev.jdguo.bank.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankRatesTest {
    @Test
    void personalQuotesKeepSixDigitsAndShowZeroAsZero() {
        JsonObject data = JsonParser.parseString("""
                {"notes":[
                  {"name":"泉","price":"0.123456"},
                  {"name":"元","price":"0.000001"},
                  {"name":"券","price":"0"}
                ]}
                """).getAsJsonObject();
        var lines = BankRates.column(data);
        assertTrue(lines.contains("1 【泉】 = 0.123456 银行券"));
        assertTrue(lines.contains("1 【元】 = 0.000001 银行券"));
        assertTrue(lines.contains("1 【券】 = 0 银行券"));
    }

    @Test
    void missingQuotesStayNamedAndCopperIsNotASeparateRow() {
        JsonObject data = new JsonObject();
        data.addProperty("status", "兑付正常");
        data.add("currencies", currencies(false));

        var lines = BankRates.column(data);

        assertTrue(lines.contains("兑付正常"));
        assertEquals("1 钻石 = 暂无牌价", BankRates.centralQuote(data));
        assertEquals("1 银行券 = 暂无牌价", BankRates.bankNoteQuote(data));
        assertTrue(lines.contains("1 钻石 = 暂无牌价"));
        assertTrue(lines.contains("1 银行券 = 暂无牌价"));
        assertFalse(String.join("\n", lines).contains("铜锭"));
        assertFalse(String.join("\n", lines).contains("各货币牌价"));
        assertFalse(String.join("\n", lines).contains("汇率"));
        assertFalse(String.join("\n", lines).contains("M0"));
    }

    @Test
    void diamondCopperIsDirectAndBankNoteIsInverted() {
        JsonObject data = new JsonObject();
        data.addProperty("copperPerDiamond", "1000");
        data.add("currencies", currencies(true));
        JsonArray notes = new JsonArray();
        JsonObject priced = new JsonObject();
        priced.addProperty("name", "张三");
        priced.addProperty("balance", "2");
        priced.addProperty("price", "2.5");
        notes.add(priced);
        JsonObject unpriced = new JsonObject();
        unpriced.addProperty("name", "李四");
        unpriced.addProperty("balance", "1");
        notes.add(unpriced);
        data.add("notes", notes);

        assertEquals("1 钻石 = 1000.000000 铜锭", BankRates.centralQuote(data));
        assertEquals("1 银行券 = 1.000000 钻石", BankRates.bankNoteQuote(data));
        assertEquals("1 【张三】 = 2.500000 银行券", BankRates.personalQuote(BankSnapshot.notes(data).get(0)));
        assertEquals("1 【李四】 = 暂无牌价", BankRates.personalQuote(BankSnapshot.notes(data).get(1)));
        var lines = BankRates.column(data);
        assertTrue(lines.contains("1 【张三】 = 2.500000 银行券"));
        assertFalse(String.join("\n", lines).contains("铜锭  "));
    }

    @Test
    void manyNotesPerDiamondBecomeASmallDiamondPrice() {
        JsonObject note = new JsonObject();
        note.addProperty("id", "note");
        note.addProperty("name", "银行券");
        note.addProperty("balance", "4");
        note.addProperty("unitsPerDiamond", "1000");
        JsonArray currencies = new JsonArray();
        currencies.add(note);
        JsonObject data = new JsonObject();
        data.add("currencies", currencies);

        assertEquals("1 银行券 = 0.001000 钻石", BankRates.bankNoteQuote(data));
        data.addProperty("diamondsPerNote", "0.5");
        assertEquals("1 银行券 = 0.500000 钻石", BankRates.bankNoteQuote(data));
    }

    private static JsonArray currencies(boolean quoted) {
        JsonArray currencies = new JsonArray();
        currencies.add(money("copper", "铜锭", "12", null));
        currencies.add(money("diamond", "钻石", "2", null));
        JsonObject note = money("note", "银行券", "4", quoted ? "1" : null);
        if (quoted) {
            note.addProperty("unitsPerDiamond", "1");
        }
        currencies.add(note);
        return currencies;
    }

    private static JsonObject money(String id, String name, String balance, String rate) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("name", name);
        row.addProperty("balance", balance);
        if (rate != null) {
            row.addProperty("rate", rate);
        }
        return row;
    }
}
