package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FourDecimalSettlementTest {
    private final UUID player = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Test
    void exchangeRefusesWhenTheNoteBalanceCannotCoverOneCopper() {
        BankBooks books = priced(new BigDecimal("0.009"));
        assertEquals(0, new BigDecimal("0.0100").compareTo(books.settlementCost("note", "copper", BigInteger.ONE)));
        BigDecimal notes = books.balanceExact(player, "note");
        BigDecimal liability = books.supply("note").liabilityExact();

        TxResult refused = books.exchange(player, "note", "copper", BigInteger.ONE);

        assertFalse(refused.success());
        assertEquals("insufficient_funds", refused.code());
        assertEquals(0, notes.compareTo(books.balanceExact(player, "note")));
        assertEquals(0, liability.compareTo(books.supply("note").liabilityExact()));
        assertEquals(0, BigDecimal.ZERO.compareTo(books.balanceExact(player, "copper")));
        assertEquals(Money.ZERO, books.supply("copper").depositVaultExact());
        books.checkInvariants();
    }

    @Test
    void exchangePostsTheWholeCopperWhenTheFourDecimalPriceIsCovered() {
        BankBooks books = priced(new BigDecimal("0.0100"));
        BigInteger copperReserve = books.supply("copper").reserve();
        assertEquals(0, new BigDecimal("0.0100").compareTo(books.settlementCost("note", "copper", BigInteger.ONE)));

        TxResult bought = books.exchange(player, "note", "copper", BigInteger.ONE);

        assertTrue(bought.success(), bought.code());
        assertEquals(0, BigDecimal.ZERO.compareTo(books.balanceExact(player, "note")));
        assertEquals(0, BigDecimal.ONE.compareTo(books.balanceExact(player, "copper")));
        assertEquals(copperReserve.subtract(BigInteger.ONE), books.supply("copper").reserve());
        books.checkInvariants();
    }

    @Test
    void withdrawalDoesNotPayAPartialStack() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(player, "copper", new BigInteger("4"));

        TxResult refused = books.withdraw(player, "copper", new BigInteger("10"), true);

        assertEquals("insufficient_funds", refused.code());
        assertEquals(new BigInteger("4"), books.balance(player, "copper"));
        assertEquals(new BigInteger("4"), books.supply("copper").depositVault());
        books.checkInvariants();
    }

    @Test
    void shopTransferRefusesAShortFourDecimalBalance() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertTrue(books.creditHeld(player, "note", new BigDecimal("0.009")).success());
        BigDecimal before = books.balanceExact(player, "note");

        TxResult refused = books.transferShopOperating(player, BigInteger.ONE, true);

        assertEquals("insufficient_funds", refused.code());
        assertEquals(0, before.compareTo(books.balanceExact(player, "note")));
        assertEquals(BigInteger.ZERO, books.shopOperating("note"));
        books.checkInvariants();
    }

    @Test
    void integerZeroStaysPlainAndAFractionRoundTrips() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertTrue(books.creditHeld(player, "note", new BigDecimal("0.009")).success());
        assertEquals("0", Money.stored(Money.ZERO));
        assertEquals("0", Money.display(Money.ZERO));
        assertEquals("0.009", Money.stored(books.balanceExact(player, "note")));

        BankBooks loaded = BankBooks.fromJson(books.toJson(), BankConfig.defaults());
        assertEquals(0, new BigDecimal("0.0090").compareTo(loaded.balanceExact(player, "note")));
        assertEquals(new BigInteger("1000000"), loaded.supply("copper").reserve());
        loaded.checkInvariants();

        String json = dev.jdguo.bank.net.BankUiSnapshot.json(loaded, player, false, "savings", false);
        assertTrue(json.contains("\"note\":\"0.009\""));
        assertTrue(json.contains("\"copper\":\"0\""));
    }

    /**
     * Outstanding notes are sized so one copper costs {@code 0.0100} notes at reserves over issuance.
     * The player's own balance is only the part named here.
     */
    private BankBooks priced(BigDecimal playerNotes) {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setFeeFraction(BigDecimal.ZERO);
        UUID others = UUID.fromString("55555555-5555-5555-5555-555555555555");
        assertTrue(books.creditHeld(player, "note", playerNotes).success());
        assertTrue(books.creditHeld(others, "note", new BigDecimal("3636.3636").subtract(playerNotes)).success());
        return books;
    }
}
