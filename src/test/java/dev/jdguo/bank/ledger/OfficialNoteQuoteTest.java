package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.net.BankUiSnapshot;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialNoteQuoteTest {
    private final UUID player = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @Test
    void noOutstandingNotesMeansNoOfficialQuote() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertEquals(BigInteger.ZERO, books.centralM1());
        assertNull(books.unitsPer("diamond", "note"));
        assertNull(books.unitsPer("note", "diamond"));
        JsonObject snapshot = JsonParser.parseString(BankUiSnapshot.json(books, player, true, "management", false)).getAsJsonObject();
        assertFalse(snapshot.has("diamondsPerNote"));
    }

    @Test
    void theQuoteIsReservesDividedByNotesOutstanding() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(player, "note", new BigInteger("2000"));
        assertEquals(0, BigDecimal.ONE.compareTo(books.unitsPer("diamond", "note")));

        books.creditUnbacked(player, "note", new BigInteger("2000"));
        assertEquals(0, new BigDecimal("0.5").compareTo(books.unitsPer("diamond", "note")));
        assertEquals(0, new BigDecimal("2").compareTo(books.unitsPer("note", "diamond")));

        books.retire(player, "note", new BigInteger("3000"));
        assertEquals(0, new BigDecimal("2").compareTo(books.unitsPer("diamond", "note")));
        assertEquals(0, new BigDecimal("0.5").compareTo(books.unitsPer("note", "diamond")));
    }

    @Test
    void emptyIssuanceReservesQuoteZeroAndDoNotInvert() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setReserve("diamond", BigInteger.ZERO);
        books.setReserve("copper", BigInteger.ZERO);
        books.creditUnbacked(player, "note", new BigInteger("8"));

        assertEquals(0, BigDecimal.ZERO.compareTo(books.unitsPer("diamond", "note")));
        assertNull(books.unitsPer("note", "diamond"));
        assertNull(books.unitsPer("note", "copper"));
        assertEquals(0, new BigDecimal(books.centralM1()).compareTo(books.centralM2()));
        JsonObject snapshot = JsonParser.parseString(BankUiSnapshot.json(books, player, false, "savings", false)).getAsJsonObject();
        assertEquals("0", snapshot.get("diamondsPerNote").getAsString());
        assertFalse(currency(snapshot, "note").has("unitsPerDiamond"));
    }

    @Test
    void reserveTransfersAndCopperQuotesMoveTheNoteQuoteThroughReservesOnly() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(player, "note", new BigInteger("2000"));
        assertEquals(0, BigDecimal.ONE.compareTo(books.unitsPer("diamond", "note")));

        assertTrue(books.transferReserve("diamond", new BigDecimal("500"), false).success());
        assertEquals(0, expected(books).compareTo(books.unitsPer("diamond", "note")));
        assertTrue(books.unitsPer("diamond", "note").compareTo(BigDecimal.ONE) < 0);

        BigDecimal copperBefore = books.unitsPer("copper", "diamond");
        books.deposit(player, "copper", new BigInteger("5000000"));
        assertTrue(books.exchange(player, "copper", "diamond", new BigInteger("400")).success());
        assertTrue(books.unitsPer("copper", "diamond").compareTo(copperBefore) > 0);
        assertEquals(0, expected(books).compareTo(books.unitsPer("diamond", "note")));
    }

    @Test
    void aFullyReservedIssueDoesNotDropLockedMetalOutOfTheQuote() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertTrue(books.creditBacked(player, "note", new BigInteger("2000")).success());
        assertEquals(new BigInteger("1000"), books.supply("diamond").locked());
        assertEquals(new BigInteger("1000000"), books.supply("copper").locked());
        assertEquals(0, BigDecimal.ONE.compareTo(books.unitsPer("diamond", "note")));
    }

    private static BigDecimal expected(BankBooks books) {
        BigDecimal copperQuote = books.unitsPer("copper", "diamond");
        BigDecimal reserves = books.supply("diamond").reserveExact()
                .add(books.supply("copper").reserveExact().divide(copperQuote, 8, RoundingMode.HALF_UP));
        return reserves.divide(books.supply("note").liabilityExact(), 4, RoundingMode.HALF_UP);
    }

    private static JsonObject currency(JsonObject snapshot, String id) {
        for (var row : snapshot.getAsJsonArray("currencies")) {
            if (id.equals(row.getAsJsonObject().get("id").getAsString())) {
                return row.getAsJsonObject();
            }
        }
        throw new AssertionError(id);
    }
}
