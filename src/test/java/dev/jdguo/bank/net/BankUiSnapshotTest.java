package dev.jdguo.bank.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.fx.FxMath;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.market.NoteRegime;
import dev.jdguo.bank.market.PersonalNote;
import dev.jdguo.bank.text.BankPhrases;
import org.junit.jupiter.api.Test;

import dev.jdguo.bank.ledger.CompactFormat;
import dev.jdguo.bank.ledger.Money;

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
    void savingsAreExactWithoutMagnitudeSuffixes() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID player = UUID.randomUUID();
        books.deposit(player, "diamond", new BigInteger("1234567"));
        assertTrue(books.createPersonalNote(player, "泉", NoteRegime.FLOAT, "diamond", BigInteger.ONE).success());
        assertTrue(books.issuePersonalNoteUnbacked(player, new BigInteger("200000")).success());
        JsonObject data = JsonParser.parseString(BankUiSnapshot.json(books, player, true, "savings", false)).getAsJsonObject();
        for (var entry : data.getAsJsonArray("currencies")) {
            var row = entry.getAsJsonObject();
            if ("diamond".equals(row.get("id").getAsString())) assertEquals("1234567", row.get("balance").getAsString());
        }
        assertEquals("200000", data.getAsJsonArray("notes").get(0).getAsJsonObject().get("balance").getAsString());
        assertEquals("200000", data.getAsJsonObject("ownNote").get("circulation").getAsString());
    }

    @Test
    void autoSaleSnapshotTracksAuthoritativeSwitchAndDynamicBalance() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID issuer = UUID.randomUUID();
        assertTrue(books.createPersonalNote(issuer, "泉", NoteRegime.FLOAT, "diamond", BigInteger.ONE).success());
        assertTrue(books.issuePersonalNoteUnbacked(issuer, BigInteger.valueOf(12)).success());
        JsonObject own = JsonParser.parseString(BankUiSnapshot.json(books, issuer, false, "issuance", false))
                .getAsJsonObject().getAsJsonObject("ownNote");
        assertTrue(own.get("autoListing").getAsBoolean());
        assertTrue(own.get("autoListingAvailable").getAsBoolean());
        assertEquals("12", own.get("offered").getAsString());
        assertTrue(books.setPersonalAutoListing(issuer, "泉", false).success());
        own = JsonParser.parseString(BankUiSnapshot.json(books, issuer, false, "issuance", false))
                .getAsJsonObject().getAsJsonObject("ownNote");
        assertFalse(own.get("autoListing").getAsBoolean());
        assertEquals("0", own.get("offered").getAsString());
    }

    @Test
    void snapshotReportsM1WithoutCountingIssuanceReservesAsSavings() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID player = UUID.randomUUID();
        books.deposit(player, "diamond", BigInteger.TEN);
        books.creditHeld(player, "note", new BigInteger("4"));
        String json = BankUiSnapshot.json(books, player, false, "savings", false);
        var o = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(o.has("m0"));
        assertFalse(o.has("m1"));
        assertFalse(o.has("m2"));
        JsonObject admin = JsonParser.parseString(BankUiSnapshot.json(books, player, true, "savings", false)).getAsJsonObject();
        assertEquals("4", admin.get("m1").getAsString());
        assertEquals("0", admin.get("m0").getAsString());
        BigDecimal exactM2 = books.centralM2();
        assertEquals(CompactFormat.formatAggregate(exactM2), admin.get("m2").getAsString());
        assertEquals(0, exactM2.setScale(4, RoundingMode.HALF_UP)
                .compareTo(new BigDecimal(admin.get("m2").getAsString().replace(",", ""))));
        assertEquals(exactM2, books.centralM2());
        assertFalse(o.has("institutions"));
        assertFalse(o.has("frozen"));
        assertFalse(o.has("feePercent"));
        assertFalse(o.has("tightness"));
        assertEquals("10", o.get("vaultDiamond").getAsString());
        assertEquals("1000", o.get("issuanceDiamond").getAsString());
        assertFalse(o.get("savings").getAsString().equals(o.get("issuanceDiamond").getAsString()));
        var names = new java.util.ArrayList<String>();
        o.getAsJsonArray("currencies").forEach(row -> names.add(row.getAsJsonObject().get("name").getAsString()));
        assertTrue(names.contains("钻石"));
        assertTrue(names.contains("铜锭"));
        assertTrue(names.contains("央行券"));
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
        assertEquals((books.supply("diamond").reserve()).toString(), reserve.get("reserve").getAsString());
        assertFalse(reserve.get("reserve").getAsString().equals(reserve.get("savings").getAsString()));
        assertEquals(Money.stored(books.unitsPer("copper", "diamond")), o.get("copperPerDiamond").getAsString());
        assertEquals(BankPhrases.copperPerDiamond(books), o.get("copperPerDiamond").getAsString());
        assertEquals("1005.0251", o.get("copperPerDiamond").getAsString());
        assertEquals("1005.0251", currency(o, "copper").get("rate").getAsString());
        assertEquals("1005.0251", currency(o, "copper").get("unitsPerDiamond").getAsString());
        assertEquals("1", currency(o, "diamond").get("rate").getAsString());
        assertEquals(Money.stored(books.unitsPer("note", "diamond")), currency(o, "note").get("rate").getAsString());
        assertEquals(Money.stored(books.unitsPer("diamond", "note")), o.get("diamondsPerNote").getAsString());
        assertEquals(Money.stored(books.unitsPer("diamond", "note")), currency(o, "note").get("diamondsPerNote").getAsString());
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
        assertFalse(snapshot.has("tightness"));
        assertTrue(snapshot.has("redemptionDetails"));
        assertTrue(snapshot.has("institutions"));
        assertEquals("0", snapshot.get("m0").getAsString());
        assertEquals("0", snapshot.get("m1").getAsString());

        JsonObject copper = central(snapshot, "copper");
        JsonObject diamond = central(snapshot, "diamond");
        JsonObject note = central(snapshot, "note");
        assertEquals("铜锭", copper.get("name").getAsString());
        assertEquals((new BigInteger("1000000")).toString(), copper.get("reserve").getAsString());
        assertEquals((books.supply("copper").liability()).toString(), copper.get("liability").getAsString());
        assertEquals((books.supply("copper").locked()).toString(), copper.get("locked").getAsString());
        assertEquals((books.supply("copper").depositVault()).toString(), copper.get("vault").getAsString());
        assertEquals((books.savingsPool("copper")).toString(), copper.get("savings").getAsString());
        assertEquals((books.backedRoom(books.currency("copper"))).toString(), copper.get("backedRoom").getAsString());

        assertEquals((new BigInteger("1000")).toString(), diamond.get("reserve").getAsString());
        assertEquals((books.supply("diamond").depositVault()).toString(), diamond.get("vault").getAsString());
        assertTrue(diamond.has("liability"));
        assertTrue(diamond.has("locked"));

        assertEquals("央行券", note.get("name").getAsString());
        assertEquals((books.supply("note").reserve()).toString(), note.get("reserve").getAsString());
        assertEquals((books.supply("note").liability()).toString(), note.get("liability").getAsString());
        assertEquals((books.supply("note").locked()).toString(), note.get("locked").getAsString());
        assertEquals((books.backedRoom(books.currency("note"))).toString(), note.get("backedRoom").getAsString());
        assertEquals((books.savingsPool("note")).toString(), note.get("savings").getAsString());
        assertFalse(note.has("vault"));
        assertEquals(new BigInteger("1000"), books.supply("note").reserve());
        assertEquals(BigInteger.ZERO, books.supply("note").liability());
    }

    /**
     * {@code price} is central 央行券 for one personal note: {@code personalDiamondPrice}
     * times the live 央行券-per-diamond quote. At the opening peg that is 1, so one personal
     * note priced at one diamond is one 央行券. {@code diamondPrice} is the diamond quote alone.
     */
    @Test
    void personalNotePriceIsCentralBankNotesAndAMissingQuoteIsOmitted() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID player = UUID.randomUUID();
        assertFalse(JsonParser.parseString(BankUiSnapshot.json(books, player, false, "savings", false))
                .getAsJsonObject().has("diamondsPerNote"));
        books.creditHeld(player, "note", new BigInteger("2000"));
        assertTrue(books.createPersonalNote(player, "泉", false, BigInteger.ONE).success());
        PersonalNote note = books.personalNote(player);
        JsonObject noCollateral = personal(JsonParser.parseString(BankUiSnapshot.json(books, player, false, "savings", false)).getAsJsonObject(), "泉");
        assertFalse(noCollateral.has("price"));
        books.deposit(player, "diamond", BigInteger.ONE);
        assertTrue(books.depositPersonalReserveExact(player, BigDecimal.ONE).success());
        assertTrue(books.issuePersonalNoteUnbacked(player, BigInteger.ONE).success());
        BigDecimal central = books.personalDiamondPrice(note).multiply(books.unitsPer("note", "diamond"), FxMath.MC);

        JsonObject quoted = JsonParser.parseString(BankUiSnapshot.json(books, player, false, "savings", false)).getAsJsonObject();
        JsonObject row = personal(quoted, "泉");
        assertEquals(Money.stored(central), row.get("price").getAsString());
        assertTrue(new BigDecimal(row.get("price").getAsString()).signum() > 0);
        assertEquals(CompactFormat.formatRate(books.personalDiamondPrice(note)), row.get("diamondPrice").getAsString());
        assertEquals(Money.stored(books.unitsPer("copper", "diamond")), quoted.get("copperPerDiamond").getAsString());
        assertEquals(Money.stored(books.unitsPer("diamond", "note")), quoted.get("diamondsPerNote").getAsString());

        books.creditHeld(player, "note", new BigInteger("1000"));
        JsonObject weak = JsonParser.parseString(BankUiSnapshot.json(books, player, false, "savings", false)).getAsJsonObject();
        assertEquals(Money.stored(books.unitsPer("note", "diamond")), currency(weak, "note").get("rate").getAsString());
        assertEquals(Money.stored(books.unitsPer("diamond", "note")), weak.get("diamondsPerNote").getAsString());
        assertTrue(new BigDecimal(weak.get("diamondsPerNote").getAsString()).compareTo(BigDecimal.ONE) < 0);

        books.setReserve("diamond", BigInteger.ZERO);
        books.setReserve("copper", BigInteger.ZERO);
        JsonObject unbacked = JsonParser.parseString(BankUiSnapshot.json(books, player, false, "savings", false)).getAsJsonObject();
        assertFalse(unbacked.has("copperPerDiamond"));
        assertFalse(unbacked.has("diamondsPerNote"));
        assertFalse(currency(unbacked, "note").has("rate"));
        assertEquals(0, new BigDecimal(books.centralM1()).compareTo(books.centralM2()));
    }

    @Test
    void floatingQuoteUnderDisplayLimitRecoversWhenReservesIncrease() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID issuer = UUID.randomUUID();
        books.creditHeld(issuer, "note", BigInteger.valueOf(2000));
        assertTrue(books.createPersonalNote(issuer, "泉", NoteRegime.FLOAT, "diamond", BigInteger.ONE).success());
        assertTrue(books.deposit(issuer, "diamond", BigInteger.TWO).success());
        assertTrue(books.depositPersonalReserve(issuer, BigInteger.ONE).success());
        assertTrue(books.issuePersonalNoteUnbacked(issuer, BigInteger.valueOf(1000)).success());
        assertEquals("0.001", diamondQuote(books, issuer));

        assertTrue(books.issuePersonalNoteUnbacked(issuer, BigInteger.valueOf(999000)).success());
        assertEquals("0.000001", diamondQuote(books, issuer));

        assertTrue(books.issuePersonalNoteUnbacked(issuer, BigInteger.valueOf(1000000)).success());
        assertEquals("0", diamondQuote(books, issuer));
        assertEquals(0, new BigDecimal("0.0000005").compareTo(books.personalDiamondPrice(books.personalNote(issuer))));

        assertTrue(books.depositPersonalReserve(issuer, BigInteger.ONE).success());
        assertEquals("0.000001", diamondQuote(books, issuer));
    }

    private static String diamondQuote(BankBooks books, UUID issuer) {
        JsonObject snapshot = JsonParser.parseString(BankUiSnapshot.json(books, issuer, false, "issuance", false))
                .getAsJsonObject();
        String quote = personal(snapshot, "泉").get("diamondPrice").getAsString();
        assertEquals(quote, snapshot.getAsJsonObject("ownNote").get("live").getAsString());
        return quote;
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
