package dev.jdguo.bank.credit;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditInflationTest {
    private final UUID player = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Test
    void unbackedIssueLowersTheReserveRatioAndWeakensNotesAgainstDiamond() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        BigDecimal before = books.unitsPer("note", "diamond");
        BigDecimal ratioBefore = books.reserveRatio("note");
        assertEquals(0, BigDecimal.ONE.compareTo(before));
        assertEquals(0, BigDecimal.ONE.compareTo(ratioBefore));

        books.creditUnbacked(player, "note", new BigInteger("1000"));

        assertEquals(0, new BigDecimal("0.5").compareTo(books.reserveRatio("note")));
        assertEquals(0, new BigDecimal("2").compareTo(books.unitsPer("note", "diamond")));
        assertTrue(books.unitsPer("note", "diamond").compareTo(before) > 0);
        assertEquals(new BigInteger("1000"), books.supply("note").reserve());
        assertEquals("准备金紧张", books.statusLabel());
        books.checkInvariants();
    }

    @Test
    void hyperinflationStaysFiniteAndDropsThePegIntoACollapseLabel() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(player, "note", BigInteger.TEN.pow(40));

        BigDecimal rate = books.unitsPer("note", "diamond");
        assertTrue(rate.compareTo(new BigDecimal("1e30")) > 0, rate.toString());
        assertTrue(books.tightness("note").compareTo(new BigDecimal("0.05")) < 0);
        assertEquals("无法兑付", books.statusLabel());
        books.checkInvariants();
    }

    @Test
    void backedIssueAfterADiamondDepositHoldsThePegAndLocksTheVault() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertEquals(BigInteger.ZERO, books.backedRoom(books.currency("note")));

        books.deposit(player, "diamond", BigInteger.TEN);
        assertEquals(BigInteger.TEN, books.backedRoom(books.currency("note")));
        BigDecimal peg = books.unitsPer("note", "diamond");

        var result = books.creditBacked(player, "note", BigInteger.TEN);

        assertTrue(result.success(), result.message());
        assertEquals(0, peg.compareTo(books.unitsPer("note", "diamond")));
        assertEquals(BigInteger.TEN, books.supply("diamond").locked());
        assertEquals(new BigInteger("1000"), books.supply("diamond").freeReserve());
        assertEquals(BigInteger.ZERO, books.backedRoom(books.currency("note")));
        assertTrue(books.withdraw(player, "diamond", BigInteger.TEN, false).success());
        assertEquals(BigInteger.TEN, books.supply("diamond").locked());
        assertEquals(BigInteger.ZERO, books.supply("diamond").freeReserve().subtract(new BigInteger("990")));
        books.checkInvariants();
    }

    @Test
    void alphaZeroPrintsMoneyWithoutMovingThePeg() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setAlpha("note", BigDecimal.ZERO);
        BigDecimal before = books.unitsPer("note", "diamond");

        books.creditUnbacked(player, "note", new BigInteger("1000"));

        assertEquals(0, before.compareTo(books.unitsPer("note", "diamond")));
        assertEquals(0, new BigDecimal("0.5").compareTo(books.reserveRatio("note")));
    }

    @Test
    void retiringUnbackedNotesStrengthensThemAgain() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(player, "note", new BigInteger("1000"));
        books.retire(player, "note", new BigInteger("1000"));

        assertEquals(0, BigDecimal.ONE.compareTo(books.unitsPer("note", "diamond")));
        assertEquals(BigInteger.ZERO, books.balance(player, "note"));
        books.checkInvariants();
    }
}
