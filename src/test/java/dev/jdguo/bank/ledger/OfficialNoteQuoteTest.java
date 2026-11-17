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
        assertNull(books.unitsPer("diamond", "note"));
        assertNull(books.unitsPer("note", "diamond"));

        books.retire(player, "note", new BigInteger("3000"));
        assertEquals(0, new BigDecimal("3").compareTo(books.unitsPer("diamond", "note")));
        assertEquals(0, new BigDecimal("0.3333").compareTo(books.unitsPer("note", "diamond")));
    }

    @Test
    void emptyIssuanceReservesQuoteZeroAndDoNotInvert() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setReserve("diamond", BigInteger.ZERO);
        books.setReserve("copper", BigInteger.ZERO);
        books.creditUnbacked(player, "note", new BigInteger("8"));

        assertNull(books.unitsPer("diamond", "note"));
        assertNull(books.unitsPer("note", "diamond"));
        assertNull(books.unitsPer("copper", "diamond"));
        assertEquals(0, new BigDecimal(books.centralM1()).compareTo(books.centralM2()));
        JsonObject snapshot = JsonParser.parseString(BankUiSnapshot.json(books, player, false, "savings", false)).getAsJsonObject();
        assertFalse(snapshot.has("diamondsPerNote"));
        assertFalse(snapshot.has("copperPerDiamond"));
        assertFalse(currency(snapshot, "note").has("unitsPerDiamond"));
    }

    @Test
    void reserveTransfersKeepMetalQuoteButChangeNoteBacking() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(player, "note", new BigInteger("2000"));
        assertEquals(0, BigDecimal.ONE.compareTo(books.unitsPer("diamond", "note")));

        BigDecimal copperBefore = books.unitsPer("copper", "diamond");
        assertTrue(books.transferReserve("diamond", new BigDecimal("500"), false).success());
        assertTrue(books.unitsPer("copper", "diamond").compareTo(copperBefore) == 0);
        assertEquals(0, new BigDecimal("0.5").compareTo(books.unitsPer("diamond", "note")));

        BankBooks fresh = BankBooks.create(BankConfig.defaults());
        fresh.creditUnbacked(player, "note", new BigInteger("1000"));
        assertEquals(0, new BigDecimal("3").compareTo(fresh.unitsPer("diamond", "note")));
        fresh.deposit(player, "copper", new BigInteger("1000"));
        assertEquals(0, new BigDecimal("1001").compareTo(fresh.unitsPer("copper", "diamond")));
        assertEquals(0, expected(fresh).compareTo(fresh.unitsPer("diamond", "note")));
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
        BigDecimal reserves = books.supply("diamond").reserveExact();
        if (copperQuote != null && copperQuote.signum() > 0) {
            reserves = reserves.add(books.supply("copper").reserveExact().divide(copperQuote, 8, RoundingMode.HALF_UP));
        }
        BigDecimal liabilities = books.supply("note").liabilityExact();
        BigDecimal alpha = books.alphaOf("note");
        BigDecimal effective = reserves.add(reserves.subtract(liabilities).multiply(alpha)
                .divide(new BigDecimal("1000"), 8, RoundingMode.HALF_UP));
        if (effective.signum() <= 0 || liabilities.signum() <= 0) {
            return null;
        }
        return effective.divide(liabilities, 4, RoundingMode.HALF_UP);
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
