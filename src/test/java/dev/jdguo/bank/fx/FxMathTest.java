package dev.jdguo.bank.fx;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxMathTest {
    @Test
    void openingCopperPerDiamondMatchesTheDatapackDisplayRate() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertEquals(0, new BigDecimal("1000").compareTo(books.unitsPer("copper", "diamond")));
        assertEquals(0, BigDecimal.ONE.compareTo(books.unitsPer("note", "diamond")));
    }

    @Test
    void feeIsChargedOnTopAndRoundedUp() {
        BigInteger cost = FxMath.cost(BigInteger.ONE, new BigDecimal("1000000"), new BigDecimal("1000"),
                new BigDecimal("0.02"), null, null);
        assertEquals(new BigInteger("1020"), cost);
    }

    @Test
    void raisingLiabilitiesWithoutReservesWeakensTheCommodityQuote() {
        BigDecimal backed = FxMath.commodityAbundance(new BigInteger("1000"), new BigInteger("1000"), new BigDecimal("1000"));
        BigDecimal printed = FxMath.commodityAbundance(new BigInteger("1000"), new BigInteger("5000"), new BigDecimal("1000"));
        assertEquals(0, backed.compareTo(new BigDecimal("1000")));
        assertTrue(printed.compareTo(backed) > 0);
    }

    @Test
    void alphaZeroIgnoresLiabilities() {
        BigDecimal peg = FxMath.virtualScale(new BigInteger("100"), new BigInteger("999999"), BigDecimal.ZERO);
        assertEquals(0, BigDecimal.ONE.compareTo(peg));
    }

    @Test
    void optionalClampCanHideASpikeAndDefaultsDoNot() {
        BigDecimal raw = FxMath.quote(new BigDecimal("1000000000000"), BigDecimal.ONE, null, null);
        assertTrue(raw.compareTo(new BigDecimal("999999")) > 0);
        BigDecimal clamped = FxMath.quote(new BigDecimal("1000000000000"), BigDecimal.ONE, null, new BigDecimal("999999"));
        assertEquals(0, new BigDecimal("999999").compareTo(clamped));
    }

    @Test
    void zeroReserveWithOutstandingNotesIsACollapse() {
        assertNull(FxMath.virtualScale(BigInteger.ZERO, BigInteger.TEN, new BigDecimal("1000")));
    }

    @Test
    void savingsDepositDoesNotMoveTheIssuanceCopperDiamondRate() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(java.util.UUID.randomUUID(), "copper", new BigInteger("1000000"));
        assertEquals(0, new BigDecimal("1000").compareTo(books.unitsPer("copper", "diamond")));
        assertEquals(new BigInteger("1000000"), books.supply("copper").reserve());
        assertEquals(new BigInteger("1000000"), books.supply("copper").depositVault());
    }
}
