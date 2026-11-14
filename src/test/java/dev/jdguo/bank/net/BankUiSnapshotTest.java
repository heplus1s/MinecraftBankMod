package dev.jdguo.bank.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.fx.FxMath;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.market.PersonalNote;
import dev.jdguo.bank.text.BankPhrases;
import org.junit.jupiter.api.Test;

import dev.jdguo.bank.ledger.CompactFormat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankUiSnapshotTest {
    @Test
    void snapshotReportsM1WithoutCountingIssuanceReservesAsSavings() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID player = UUID.randomUUID();
        books.deposit(player, "diamond", BigInteger.TEN);
        books.creditUnbacked(player, "note", new BigInteger("4"));
        String json = BankUiSnapshot.json(books, player, false, "savings", false);
        var o = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("4", o.get("m1").getAsString());
        assertEquals("0", o.get("m0").getAsString());
        BigDecimal exactM2 = books.centralM2();
        assertEquals(CompactFormat.formatAggregate(exactM2), o.get("m2").getAsString());
        assertEquals(0, exactM2.setScale(6, RoundingMode.HALF_UP)
                .compareTo(new BigDecimal(o.get("m2").getAsString().replace(",", ""))));
        assertEquals(exactM2, books.centralM2());
        assertFalse(o.has("institutions"));
        assertFalse(o.has("frozen"));
        assertFalse(o.has("feePercent"));
        assertFalse(o.has("tightness"));
        assertEquals("10", o.get("vaultDiamond").getAsString());
        assertEquals("1000", o.get("issuanceDiamond").getAsString());
        assertFalse(o.get("savings").getAsString().equals(o.get("issuanceDiamond").getAsString()));
        assertTrue(o.get("m2").getAsString().length() > 0);
        var names = new java.util.ArrayList<String>();
        o.getAsJsonArray("currencies").forEach(row -> names.add(row.getAsJsonObject().get("name").getAsString()));
        assertTrue(names.contains("钻石"));
        assertTrue(names.contains("铜锭"));
        assertTrue(names.contains("银行券"));
        assertFalse(names.contains("diamond"));
        assertFalse(names.contains("copper"));
        JsonObject diamond = null;
        for (var row : o.getAsJsonArray("accounts")) {
            if ("钻石".equals(row.getAsJsonObject().get("name").getAsString())) {
                diamond = row.getAsJsonObject();
            }
        }
        assertNotNull(diamond);
        assertEquals("10", diamond.get("amount").getAsString());
        JsonObject reserve = null;
        for (var row : o.getAsJsonArray("central")) {
            if ("钻石".equals(row.getAsJsonObject().get("name").getAsString())) {
                reserve = row.getAsJsonObject();
            }
        }
        assertNotNull(reserve);
        assertEquals(dev.jdguo.bank.ledger.CompactFormat.format(books.supply("diamond").reserve()), reserve.get("reserve").getAsString());
        assertFalse(reserve.get("reserve").getAsString().equals(reserve.get("savings").getAsString()));
        assertEquals("1000", o.get("copperPerDiamond").getAsString());
        assertEquals(BankPhrases.copperPerDiamond(books), o.get("copperPerDiamond").getAsString());
        assertEquals("1000", currency(o, "copper").get("rate").getAsString());
        assertEquals("1000", currency(o, "copper").get("unitsPerDiamond").getAsString());
        assertEquals("1", currency(o, "diamond").get("rate").getAsString());
        assertEquals(CompactFormat.formatRate(books.unitsPer("note", "diamond")), currency(o, "note").get("rate").getAsString());
        assertEquals("1", currency(o, "note").get("rate").getAsString());
        assertEquals(CompactFormat.formatRate(books.unitsPer("diamond", "note")), o.get("diamondsPerNote").getAsString());
        assertEquals("1", o.get("diamondsPerNote").getAsString());
        assertEquals("1", currency(o, "note").get("diamondsPerNote").getAsString());
        assertFalse(currency(o, "copper").has("diamondsPerNote"));
    }

    @Test
    void adminBookListsEveryCurrencyReserveLiabilityAndIssuanceRoom() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID player = UUID.randomUUID();
        assertEquals(new BigInteger("1000000"), books.supply("copper").reserve());
        assertEquals(new BigInteger("1000"), books.supply("diamond").reserve());
        JsonObject snapshot = JsonParser.parseString(
                BankUiSnapshot.json(books, player, true, "management", false)).getAsJsonObject();

        assertTrue(snapshot.get("admin").getAsBoolean());
        assertFalse(snapshot.get("frozen").getAsBoolean());
        assertEquals("2%", snapshot.get("feePercent").getAsString());
        assertEquals(CompactFormat.formatRatio(books.tightness("note")), snapshot.get("tightness").getAsString());
        assertTrue(snapshot.has("institutions"));
        assertEquals("0", snapshot.get("m0").getAsString());
        assertEquals("0", snapshot.get("m1").getAsString());

        JsonObject copper = central(snapshot, "copper");
        JsonObject diamond = central(snapshot, "diamond");
        JsonObject note = central(snapshot, "note");
        assertEquals("铜锭", copper.get("name").getAsString());
        assertEquals(CompactFormat.format(new BigInteger("1000000")), copper.get("reserve").getAsString());
        assertEquals(CompactFormat.format(books.supply("copper").liability()), copper.get("liability").getAsString());
        assertEquals(CompactFormat.format(books.supply("copper").locked()), copper.get("locked").getAsString());
        assertEquals(CompactFormat.format(books.supply("copper").depositVault()), copper.get("vault").getAsString());
        assertEquals(CompactFormat.format(books.savingsPool("copper")), copper.get("savings").getAsString());
        assertEquals(CompactFormat.format(books.backedRoom(books.currency("copper"))), copper.get("backedRoom").getAsString());

        assertEquals(CompactFormat.format(new BigInteger("1000")), diamond.get("reserve").getAsString());
        assertEquals(CompactFormat.format(books.supply("diamond").depositVault()), diamond.get("vault").getAsString());
        assertTrue(diamond.has("liability"));
        assertTrue(diamond.has("locked"));

        assertEquals("银行券", note.get("name").getAsString());
        assertEquals(CompactFormat.format(books.supply("note").reserve()), note.get("reserve").getAsString());
        assertEquals(CompactFormat.format(books.supply("note").liability()), note.get("liability").getAsString());
        assertEquals(CompactFormat.format(books.supply("note").locked()), note.get("locked").getAsString());
        assertEquals(CompactFormat.format(books.backedRoom(books.currency("note"))), note.get("backedRoom").getAsString());
        assertEquals(CompactFormat.format(books.savingsPool("note")), note.get("savings").getAsString());
        assertFalse(note.has("vault"));
        assertEquals(new BigInteger("1000"), books.supply("note").reserve());
        assertEquals(BigInteger.ZERO, books.supply("note").liability());
    }

    /**
     * {@code price} is central 银行券 for one personal note: {@code personalDiamondPrice}
     * times the live 银行券-per-diamond quote. At the opening peg that is 1, so one personal
     * note priced at one diamond is one 银行券. {@code diamondPrice} is the diamond quote alone.
     */
    @Test
    void personalNotePriceIsCentralBankNotesAndAMissingQuoteIsOmitted() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID player = UUID.randomUUID();
        assertTrue(books.createPersonalNote(player, "泉", false, BigInteger.ONE).success());
        PersonalNote note = books.personalNote(player);
        BigDecimal central = books.personalDiamondPrice(note).multiply(books.unitsPer("note", "diamond"), FxMath.MC);

        JsonObject quoted = JsonParser.parseString(BankUiSnapshot.json(books, player, false, "savings", false)).getAsJsonObject();
        JsonObject row = personal(quoted, "泉");
        assertEquals(CompactFormat.formatRate(central), row.get("price").getAsString());
        assertEquals("1", row.get("price").getAsString());
        assertEquals(CompactFormat.formatRate(books.personalDiamondPrice(note)), row.get("diamondPrice").getAsString());
        assertEquals("1000", quoted.get("copperPerDiamond").getAsString());

        books.creditUnbacked(player, "note", new BigInteger("1000"));
        JsonObject weak = JsonParser.parseString(BankUiSnapshot.json(books, player, false, "savings", false)).getAsJsonObject();
        assertEquals("2", currency(weak, "note").get("rate").getAsString());
        assertEquals("0.5", weak.get("diamondsPerNote").getAsString());
        assertEquals("0.5", currency(weak, "note").get("diamondsPerNote").getAsString());
        assertEquals(CompactFormat.formatRate(books.unitsPer("diamond", "note")), weak.get("diamondsPerNote").getAsString());

        books.setReserve("diamond", BigInteger.ZERO);
        JsonObject collapsed = JsonParser.parseString(BankUiSnapshot.json(books, player, false, "savings", false)).getAsJsonObject();
        assertFalse(collapsed.has("copperPerDiamond"));
        assertFalse(collapsed.has("diamondsPerNote"));
        assertFalse(currency(collapsed, "note").has("rate"));
        assertFalse(currency(collapsed, "note").has("diamondsPerNote"));
        assertFalse(personal(collapsed, "泉").has("price"));
        assertFalse(personal(collapsed, "泉").has("diamondPrice"));
        assertFalse(personal(collapsed, "泉").has("rate"));
    }

    private static JsonObject central(JsonObject snapshot, String id) {
        for (var row : snapshot.getAsJsonArray("central")) {
            if (id.equals(row.getAsJsonObject().get("id").getAsString())) {
                return row.getAsJsonObject();
            }
        }
        throw new AssertionError(id);
    }

    private static JsonObject currency(JsonObject snapshot, String id) {
        for (var row : snapshot.getAsJsonArray("currencies")) {
            if (id.equals(row.getAsJsonObject().get("id").getAsString())) {
                return row.getAsJsonObject();
            }
        }
        throw new AssertionError(id);
    }

    private static JsonObject personal(JsonObject snapshot, String name) {
        for (var row : snapshot.getAsJsonArray("notes")) {
            if (name.equals(row.getAsJsonObject().get("name").getAsString())) {
                return row.getAsJsonObject();
            }
        }
        throw new AssertionError(name);
    }
}
