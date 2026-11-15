package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssuanceSavingsTest {
    private final UUID operator = UUID.fromString("12121212-1212-1212-1212-121212121212");

    @Test
    void issuingNotesRaisesTheCentralLiabilityAndNotPersonalSavings() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(operator, "copper", new BigInteger("256"));
        books.deposit(operator, "diamond", new BigInteger("320"));
        BigDecimal notes = books.balanceExact(operator, "note");
        BigDecimal liability = books.supply("note").liabilityExact();

        assertTrue(books.creditBacked(operator, "note", new BigInteger("10")).success());
        assertTrue(books.creditUnbacked(operator, "note", new BigInteger("200000")).success());

        assertEquals(0, notes.compareTo(books.balanceExact(operator, "note")));
        assertEquals(0, liability.add(new BigDecimal("200010")).compareTo(books.supply("note").liabilityExact()));
        assertEquals(new BigInteger("256"), books.balance(operator, "copper"));
        assertEquals(new BigInteger("320"), books.balance(operator, "diamond"));
    }

    @Test
    void retiringNotesLowersTheCentralLiabilityAndNotPersonalSavings() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertTrue(books.creditHeld(operator, "note", new BigInteger("40")).success());
        assertTrue(books.creditUnbacked(operator, "note", new BigInteger("100")).success());
        BigDecimal savings = books.balanceExact(operator, "note");
        BigDecimal liability = books.supply("note").liabilityExact();

        TxResult retired = books.retire(operator, "note", new BigInteger("100"));

        assertTrue(retired.success(), retired.code());
        assertEquals(0, savings.compareTo(books.balanceExact(operator, "note")));
        assertEquals(0, liability.subtract(new BigDecimal("100")).compareTo(books.supply("note").liabilityExact()));

        TxResult intoSavings = books.retire(operator, "note", BigInteger.ONE);
        assertEquals("insufficient_issue", intoSavings.code());
        assertEquals(0, savings.compareTo(books.balanceExact(operator, "note")));
    }
}
