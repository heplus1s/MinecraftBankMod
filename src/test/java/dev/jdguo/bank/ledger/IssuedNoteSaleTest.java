package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.text.BankPhrases;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssuedNoteSaleTest {
    private final UUID player = UUID.fromString("56565656-5656-5656-5656-565656565656");

    @Test
    void metalSavingsBuyAlreadyIssuedNotesWithoutMinting() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setFeeFraction(BigDecimal.ZERO);
        assertTrue(books.creditUnbacked(player, "note", new BigInteger("100")).success());
        assertTrue(books.deposit(player, "diamond", new BigInteger("50")).success());
        BigDecimal quote = books.unitsPer("diamond", "note");
        BigDecimal vault = books.supply("diamond").depositVaultExact();
        BigInteger reserve = books.supply("diamond").reserve();
        BigDecimal liability = books.supply("note").liabilityExact();
        BigInteger m1 = books.centralM1();

        TxResult bought = books.exchange(player, "diamond", "note", BigInteger.ONE);

        assertTrue(bought.success(), bought.code());
        assertEquals(0, liability.compareTo(books.supply("note").liabilityExact()));
        assertEquals(reserve, books.supply("diamond").reserve());
        assertEquals(0, vault.add(quote).compareTo(books.supply("diamond").depositVaultExact()));
        assertEquals(0, new BigDecimal("50").subtract(quote).compareTo(books.balanceExact(player, "diamond")));
        assertEquals(0, BigDecimal.ONE.compareTo(books.balanceExact(player, "note")));
        assertEquals(m1.add(BigInteger.ONE), books.centralM1());
    }

    @Test
    void nothingIsSoldWhenNoIssuedNotesAreUnallocatedOrSavingsAreShort() {
        BankBooks bare = BankBooks.create(BankConfig.defaults());
        bare.setFeeFraction(BigDecimal.ZERO);
        assertTrue(bare.creditUnbacked(player, "note", new BigInteger("100")).success());
        assertTrue(bare.deposit(player, "diamond", new BigInteger("4000")).success());
        assertTrue(bare.exchange(player, "diamond", "note", new BigInteger("100")).success());
        BigDecimal diamonds = bare.balanceExact(player, "diamond");
        BigDecimal vault = bare.supply("diamond").depositVaultExact();
        BigDecimal notes = bare.balanceExact(player, "note");

        TxResult none = bare.exchange(player, "diamond", "note", BigInteger.ONE);

        assertEquals("no_unallocated", none.code());
        assertTrue(BankPhrases.failure(BankPhrases.Kind.EXCHANGE, none.code(), "note", "diamond").zh().contains("尚未入账"));
        assertEquals(0, diamonds.compareTo(bare.balanceExact(player, "diamond")));
        assertEquals(0, vault.compareTo(bare.supply("diamond").depositVaultExact()));
        assertEquals(0, notes.compareTo(bare.balanceExact(player, "note")));
        assertEquals(0, new BigDecimal("100").compareTo(bare.supply("note").liabilityExact()));

        BankBooks thin = BankBooks.create(BankConfig.defaults());
        thin.setFeeFraction(BigDecimal.ZERO);
        assertTrue(thin.creditUnbacked(player, "note", new BigInteger("100")).success());
        assertTrue(thin.deposit(player, "diamond", BigInteger.ONE).success());
        BigDecimal one = thin.balanceExact(player, "diamond");

        TxResult poor = thin.exchange(player, "diamond", "note", BigInteger.ONE);

        assertEquals("insufficient_funds", poor.code());
        assertEquals(0, one.compareTo(thin.balanceExact(player, "diamond")));
        assertEquals(0, BigDecimal.ZERO.compareTo(thin.balanceExact(player, "note")));
        assertEquals(0, new BigDecimal("100").compareTo(thin.supply("note").liabilityExact()));
    }
}
