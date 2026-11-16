package dev.jdguo.bank.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankBookTest {
    @Test
    void integersDropTrailingZerosAndMissingFieldsStayMissing() {
        JsonObject data = snapshot(false);
        data.addProperty("m0", "0.000000");
        data.addProperty("m1", "0.000000");
        data.addProperty("m2", "0.000000");
        data.addProperty("status", "兑付正常");

        List<String> preface = BankBook.preface(data);

        assertTrue(preface.contains("兑付状态：兑付正常"));
        assertTrue(preface.contains("银行券兑换铜锭或钻石：正常"));
        assertTrue(preface.contains("流通中现金（M0）：0"));
        assertTrue(preface.contains("狭义货币（M1）：0"));
        assertTrue(preface.contains("广义货币（M2）：0"));
        assertTrue(preface.contains(BankBook.CIRCULATION_NOTE));
        assertFalse(String.join("\n", preface).contains("0.000000"));
        assertEquals(BankBook.PAUSE, BankBook.freezeButton(data));
        assertEquals(List.of(
                "准备金：1000",
                "央行储蓄：20",
                "存款保管：7",
                "发行额：暂无数据",
                "已锁定准备金：暂无数据",
                "按准备金尚可发行：暂无数据"), BankBook.book(data, "diamond", "钻石"));
        assertEquals(List.of("商店经营账户和土地出让账户：暂无数据"), BankBook.institutions(data));
        List<String> ledger = BankBook.visibleLedger(data);
        assertTrue(ledger.contains("铜锭储蓄账面总值：暂无数据"));
        assertTrue(ledger.contains("铜锭实际硬通货储蓄总值：暂无数据"));
        assertTrue(ledger.contains("钻石储蓄账面总值：暂无数据"));
        assertTrue(ledger.contains("钻石实际硬通货储蓄总值：暂无数据"));
        assertTrue(ledger.contains("铜锭准备金：1000000"));
        assertTrue(ledger.contains("铜锭央行储蓄：4"));
        assertTrue(ledger.contains("钻石准备金：1000"));
        assertTrue(ledger.contains("钻石央行储蓄：20"));
        assertTrue(ledger.contains("银行券发行准备金：1000000"));
        assertTrue(ledger.contains("银行券央行储蓄：3"));
        assertTrue(ledger.contains("铜锭存款保管：9"));
        assertFalse(String.join("\n", ledger).contains("银行券存款保管"));
        assertTrue(ledger.contains("流通中现金（M0）：0"));
        assertTrue(BankBook.actionNotes().contains(BankBook.BACKED_EXPLANATION));
        assertFalse(String.join("\n", BankBook.actionNotes()).contains("柜台还没有这个动作"));
    }

    @Test
    void frozenStatusUsesTheResumeButtonAndRicherCentralFields() {
        JsonObject data = snapshot(true);
        data.addProperty("frozen", true);
        data.addProperty("status", "暂停兑付");
        data.addProperty("tightness", "0.250000");
        data.addProperty("feePercent", "2");
        JsonArray central = data.getAsJsonArray("central");
        JsonObject note = central.get(2).getAsJsonObject();
        note.addProperty("liability", "5000");
        note.addProperty("locked", "10");
        note.addProperty("backedRoom", "990");
        JsonArray institutions = new JsonArray();
        JsonObject shop = new JsonObject();
        shop.addProperty("name", "商店经营账户");
        shop.addProperty("detail", "余额 40");
        institutions.add(shop);
        data.add("institutions", institutions);

        assertEquals("银行券兑换铜锭或钻石：已暂停", BankBook.redemption(data));
        assertEquals(BankBook.RESUME, BankBook.freezeButton(data));
        assertTrue(BankBook.preface(data).contains("兑付能力：0.2500"));
        assertTrue(BankBook.preface(data).contains("手续费：2%"));
        assertEquals(List.of(
                "准备金：1000000",
                "央行储蓄：3",
                "存款保管：暂无数据",
                "发行额：5000",
                "已锁定准备金：10",
                "按准备金尚可发行：990"), BankBook.book(data, "note", "银行券"));
        assertEquals(List.of("商店经营账户：余额 40"), BankBook.institutions(data));
        data.addProperty("copperBookSavings", "10.5");
        data.addProperty("copperPhysicalSavings", "10");
        List<String> compared = BankBook.visibleLedger(data);
        assertTrue(compared.contains("铜锭储蓄账面总值：10.5000"));
        assertTrue(compared.contains("铜锭实际硬通货储蓄总值：10"));
        JsonArray hardBooks = new JsonArray();
        JsonObject copper = new JsonObject();
        copper.addProperty("id", "copper");
        copper.addProperty("savingsBook", "20.5");
        copper.addProperty("depositVault", "18");
        copper.addProperty("reserve", "999");
        hardBooks.add(copper);
        data.add("hardBooks", hardBooks);
        List<String> fromHardBooks = BankBook.visibleLedger(data);
        assertTrue(fromHardBooks.contains("铜锭储蓄账面总值：20.5000"));
        assertTrue(fromHardBooks.contains("铜锭实际硬通货储蓄总值：18"));
        assertTrue(fromHardBooks.contains("铜锭准备金：1000000"));
        assertEquals("铜锭储蓄账面总值：20.5000", BankBook.metalLines(data, "copper", "铜锭", "copperReserve").get(0));
        assertEquals("铜锭实际硬通货储蓄总值：18", BankBook.metalLines(data, "copper", "铜锭", "copperReserve").get(1));
        assertTrue(BankBook.noteLines(data).contains("银行券发行准备金：1000000"));
        assertTrue(BankBook.noteLines(data).contains("银行券发行额：5000"));
        assertFalse(String.join("\n", BankBook.metalLines(data, "copper", "铜锭", "copperReserve")).contains("发行额"));
    }

    private static JsonObject snapshot(boolean frozen) {
        JsonObject data = new JsonObject();
        data.addProperty("admin", true);
        data.addProperty("frozen", frozen);
        JsonArray central = new JsonArray();
        central.add(centralRow("copper", "铜锭", "1000000", "4", "9"));
        central.add(centralRow("diamond", "钻石", "1000", "20", "7"));
        central.add(centralRow("note", "银行券", "1000000", "3", null));
        data.add("central", central);
        return data;
    }

    private static JsonObject centralRow(String id, String name, String reserve, String savings, String vault) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("name", name);
        row.addProperty("reserve", reserve);
        row.addProperty("savings", savings);
        if (vault != null) {
            row.addProperty("vault", vault);
        }
        return row;
    }
}
