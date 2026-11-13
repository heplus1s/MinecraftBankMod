package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankLedgerTest {
    private final UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void depositCreditsBalanceReserveAndLiabilityTogether() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        BigInteger reserve = books.supply("copper").reserve();
        BigInteger liability = books.supply("copper").liability();

        TxResult result = books.deposit(player, "copper", BigInteger.valueOf(64));

        assertTrue(result.success());
        assertEquals(BigInteger.valueOf(64), books.balance(player, "copper"));
        assertEquals(reserve.add(BigInteger.valueOf(64)), books.supply("copper").reserve());
        assertEquals(liability.add(BigInteger.valueOf(64)), books.supply("copper").liability());
        books.checkInvariants();
    }

    @Test
    void debitRejectsInsufficientFundsAndDoesNotChangeBooks() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(player, "diamond", BigInteger.TEN);

        TxResult result = books.withdraw(player, "diamond", BigInteger.valueOf(11), false);

        assertFalse(result.success());
        assertEquals("insufficient_funds", result.code());
        assertEquals(BigInteger.TEN, books.balance(player, "diamond"));
        books.checkInvariants();
    }

    @Test
    void withdrawFailsWhenTheVaultCannotPay() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(player, "diamond", BigInteger.valueOf(10));
        books.setReserve("diamond", BigInteger.valueOf(3));

        TxResult exact = books.withdraw(player, "diamond", BigInteger.valueOf(10), false);
        assertEquals("insufficient_reserves", exact.code());
        assertEquals(BigInteger.valueOf(10), books.balance(player, "diamond"));

        TxResult partial = books.withdraw(player, "diamond", BigInteger.valueOf(10), true);
        assertTrue(partial.success());
        assertEquals(BigInteger.valueOf(3), partial.amount());
        assertEquals(BigInteger.valueOf(7), books.balance(player, "diamond"));
        assertEquals(BigInteger.ZERO, books.supply("diamond").reserve());
        books.checkInvariants();
    }

    @Test
    void exchangeMovesLiabilitiesAndLeavesReservesUntouched() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(player, "copper", new BigInteger("5000"));
        BigInteger copperReserve = books.supply("copper").reserve();
        BigInteger diamondReserve = books.supply("diamond").reserve();
        BigInteger qty = BigInteger.ONE;
        BigInteger expected = books.cost("copper", "diamond", qty);

        TxResult result = books.exchange(player, "copper", "diamond", qty);

        assertTrue(result.success());
        assertEquals(expected, result.amount());
        assertEquals(new BigInteger("5000").subtract(expected), books.balance(player, "copper"));
        assertEquals(qty, books.balance(player, "diamond"));
        assertEquals(copperReserve, books.supply("copper").reserve());
        assertEquals(diamondReserve, books.supply("diamond").reserve());
        books.checkInvariants();
    }

    @Test
    void exchangeIntoACommodityStopsAtFreeVaultHeadroom() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(player, "copper", new BigInteger("50000000"));
        books.setReserve("diamond", BigInteger.valueOf(2));

        TxResult result = books.exchange(player, "copper", "diamond", BigInteger.TEN);

        assertEquals("insufficient_reserves", result.code());
        assertEquals(BigInteger.ZERO, books.balance(player, "diamond"));
        books.checkInvariants();
    }

    @Test
    void frozenConvertibilityBlocksPaperIntoHardCurrencyOnly() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(player, "note", new BigInteger("1000"));
        books.deposit(player, "diamond", BigInteger.TEN);
        books.setFrozen(true);

        assertEquals("frozen", books.exchange(player, "note", "diamond", BigInteger.ONE).code());
        assertTrue(books.exchange(player, "diamond", "note", BigInteger.ONE).success());
        assertTrue(books.withdraw(player, "diamond", BigInteger.ONE, false).success());
        books.checkInvariants();
    }

    @Test
    void hugeBalanceDoesNotOverflow() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        BigInteger huge = BigInteger.TEN.pow(100);
        TxResult result = books.creditUnbacked(player, "note", huge);

        assertTrue(result.success());
        assertEquals(huge, books.balance(player, "note"));
        assertTrue(books.supply("note").liability().compareTo(huge) > 0);
        String shown = CompactFormat.format(books.balance(player, "note"));
        assertTrue(shown.contains("e"), shown);
        books.checkInvariants();
    }

    @Test
    void amountsParseSuffixesAndScientificNotation() {
        assertEquals(new BigInteger("1500"), Amounts.parse("1.5k"));
        assertEquals(new BigInteger("1000000"), Amounts.parse("1_000_000"));
        assertEquals(BigInteger.TEN.pow(40), Amounts.parse("1e40"));
    }
}
