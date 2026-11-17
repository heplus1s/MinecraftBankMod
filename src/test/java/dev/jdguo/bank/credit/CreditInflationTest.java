package dev.jdguo.bank.credit;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditInflationTest {
    private final UUID player = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Test
    void unbackedIssuePricesTheNoteAtReservesOverLiabilities() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertNull(books.unitsPer("diamond", "note"));
        assertEquals(0, BigDecimal.ONE.compareTo(books.reserveRatio("note")));

        books.creditUnbacked(player, "note", new BigInteger("1000"));

        assertEquals(0, new BigDecimal("0.5").compareTo(books.reserveRatio("note")));
        assertEquals(0, new BigDecimal("3").compareTo(books.unitsPer("diamond", "note")));
        assertEquals(0, new BigDecimal("0.3333").compareTo(books.unitsPer("note", "diamond")));
        assertEquals(new BigInteger("1000"), books.supply("note").reserve());
        assertEquals("可兑付（以所选实物可用准备金为限）", books.statusLabel());
        books.checkInvariants();
    }

    @Test
    void hyperinflationStaysFiniteAndDropsThePegIntoACollapseLabel() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(player, "note", BigInteger.TEN.pow(40));

        assertNull(books.unitsPer("diamond", "note"));
        assertNull(books.unitsPer("note", "diamond"));
        assertEquals(0, new BigDecimal(books.centralM1()).compareTo(books.centralM2()));
        assertTrue(books.tightness("note").compareTo(new BigDecimal("0.05")) < 0);
        assertEquals("暂无可用兑付牌价", books.statusLabel());
        books.checkInvariants();
    }

    @Test
    void backedIssueKeepsLockedMetalInsideTheQuote() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertEquals(new BigInteger("2000"), books.backedRoom(books.currency("note")));
        books.deposit(player, "diamond", BigInteger.TEN);
        assertEquals(new BigInteger("1000"), books.supply("diamond").reserve());
        assertEquals(new BigInteger("1995"), books.backedRoom(books.currency("note")));

        var result = books.creditBacked(player, "note", BigInteger.TEN);

        assertTrue(result.success(), result.message());
        assertTrue(books.unitsPer("diamond", "note").compareTo(new BigDecimal("390")) > 0);
        assertEquals(BigInteger.TEN, books.supply("diamond").locked());
        assertEquals(new BigInteger("990"), books.supply("diamond").freeReserve());
        assertEquals(new BigInteger("1985"), books.backedRoom(books.currency("note")));
        assertTrue(books.withdraw(player, "diamond", BigInteger.TEN, false).success());
        assertEquals(BigInteger.TEN, books.supply("diamond").locked());
        assertEquals(new BigInteger("990"), books.supply("diamond").freeReserve());
        books.checkInvariants();
    }

    @Test
    void alphaZeroPricesNotesAtReservesOverLiabilities() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setAlpha("note", BigDecimal.ZERO);

        books.creditUnbacked(player, "note", new BigInteger("1000"));

        assertEquals(0, new BigDecimal("2").compareTo(books.unitsPer("diamond", "note")));
        assertEquals(0, new BigDecimal("0.5").compareTo(books.reserveRatio("note")));
    }

    @Test
    void retiringTheLastNotesClearsTheOfficialQuote() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(player, "note", new BigInteger("1000"));
        books.retire(player, "note", new BigInteger("1000"));

        assertNull(books.unitsPer("diamond", "note"));
        assertNull(books.unitsPer("note", "diamond"));
        assertEquals(BigInteger.ZERO, books.balance(player, "note"));
        books.checkInvariants();
    }
}
