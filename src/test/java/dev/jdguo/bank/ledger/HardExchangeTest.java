package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class HardExchangeTest {
    private final UUID player = UUID.randomUUID();
    private BankBooks books() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setFeeFraction(new BigDecimal("0.02"));
        books.creditUnbacked(player, "note", new BigInteger("100"));
        books.deposit(player, "diamond", BigInteger.TEN);
        return books;
    }
    @Test void integerMetalSellsForFractionalNotesWithoutDuplicatingCustodyOrIssuance() {
        BankBooks b = books();
        BigDecimal vault = b.supply("diamond").depositVaultExact(), liability = b.supply("note").liabilityExact();
        HardExchangeQuote q = b.quoteHardExchange("diamond", "note", BigInteger.ONE);
        assertNotNull(q);
        assertEquals(0, BigDecimal.ONE.compareTo(q.debit()));
        assertTrue(b.confirmHardExchange(player, q).success());
        assertEquals(0, new BigDecimal("9").compareTo(b.balanceExact(player, "diamond")));
        assertEquals(0, q.credit().compareTo(b.balanceExact(player, "note")));
        assertEquals(vault.subtract(BigDecimal.ONE), b.supply("diamond").depositVaultExact());
        assertEquals(0, BigDecimal.ONE.compareTo(b.treasuryExact("diamond")));
        assertEquals(liability, b.supply("note").liabilityExact());
        b.checkInvariants();
    }
    @Test void buyingWholePhysicalCostsFractionalNotes() {
        BankBooks b = books();
        b.creditHeld(player, "note", new BigDecimal("100.1234"));
        HardExchangeQuote q = b.quoteHardExchange("note", "copper", BigInteger.ONE);
        assertNotNull(q);
        BigDecimal before = b.balanceExact(player, "note");
        assertTrue(b.confirmHardExchange(player, q).success());
        assertEquals(0, BigDecimal.ONE.compareTo(b.balanceExact(player, "copper")));
        assertEquals(0, before.subtract(q.debit()).compareTo(b.balanceExact(player, "note")));
        b.checkInvariants();
    }
    @Test void changedFeeAndChangedRateBothRefuseWithoutMutation() {
        BankBooks b = books();
        HardExchangeQuote q = b.quoteHardExchange("diamond", "note", BigInteger.ONE);
        b.setFeeFraction(new BigDecimal("0.03"));
        String before = b.toJson().toString();
        assertEquals("quote_changed", b.confirmHardExchange(player, q).code());
        assertEquals(before, b.toJson().toString());
        q = b.quoteHardExchange("diamond", "note", BigInteger.ONE);
        b.creditUnbacked(player, "note", BigInteger.TEN);
        before = b.toJson().toString();
        assertEquals("quote_changed", b.confirmHardExchange(player, q).code());
        assertEquals(before, b.toJson().toString());
    }
    @Test void legacyFractionIsPreservedAcrossExchangeAndSaveLoad() {
        BankBooks b = books();
        var json = com.google.gson.JsonParser.parseString(b.toJson()).getAsJsonObject();
        json.getAsJsonObject("accounts").getAsJsonObject(player.toString()).addProperty("diamond", "10.032");
        b = BankBooks.fromJson(json.toString(), BankConfig.defaults());
        assertTrue(b.confirmHardExchange(player, b.quoteHardExchange("diamond", "note", BigInteger.ONE)).success());
        b = BankBooks.fromJson(b.toJson(), BankConfig.defaults());
        assertEquals(0, new BigDecimal("9.032").compareTo(b.balanceExact(player, "diamond")));
        b.checkInvariants();
    }
    @Test void invalidAndInsufficientTransactionsAreAtomic() {
        BankBooks b = books();
        assertNull(b.quoteHardExchange("diamond", "note", BigInteger.ZERO));
        assertNull(b.quoteHardExchange("diamond", "note", BigInteger.valueOf(-1)));
        assertNull(b.quoteHardExchange("diamond", "note", BigInteger.TEN.pow(100)));
        assertNull(b.quoteHardExchange("diamond", "copper", BigInteger.ONE));
        HardExchangeQuote q = b.quoteHardExchange("diamond", "note", BigInteger.valueOf(11));
        String before = b.toJson().toString();
        assertEquals("insufficient_funds", b.confirmHardExchange(player, q).code());
        assertEquals(before, b.toJson().toString());
        q = b.quoteHardExchange("note", "diamond", BigInteger.ONE);
        assertEquals("insufficient_funds", b.confirmHardExchange(player, q).code());
        assertEquals(before, b.toJson().toString());
    }
    @Test void legacyEntryRefusesFractionalMetalRatherThanRoundingOrDuplicatingIt() {
        BankBooks b = books();
        b.deposit(player, "diamond", BigInteger.valueOf(50));
        String before = b.toJson().toString();
        assertEquals("whole_physical_required", b.exchange(player, "diamond", "note", BigInteger.ONE).code());
        assertEquals(before, b.toJson().toString());
    }
    @Test void spreadNeverImprovesAConstantRateRoundTrip() {
        BankBooks b = books();
        for (int n : new int[]{1, 5, 64, 4096}) {
            HardExchangeQuote sell = b.quoteHardExchange("diamond", "note", BigInteger.valueOf(n));
            HardExchangeQuote buy = b.quoteHardExchange("note", "diamond", BigInteger.valueOf(n));
            assertNotNull(sell); assertNotNull(buy);
            assertTrue(sell.credit().compareTo(buy.debit()) <= 0);
            assertEquals(0, sell.debit().compareTo(buy.credit()));
        }
    }
    @Test void outstandingNoteShortageAndFrozenRedemptionRefuseAtomically() {
        BankBooks b = BankBooks.create(BankConfig.defaults());
        b.creditHeld(player, "note", BigInteger.TEN);
        b.deposit(player, "diamond", BigInteger.TEN);
        var q = b.quoteHardExchange("diamond", "note", BigInteger.ONE);
        String before = b.toJson();
        assertEquals("no_unallocated", b.confirmHardExchange(player, q).code());
        assertEquals(before, b.toJson());
        q = b.quoteHardExchange("note", "diamond", BigInteger.ONE);
        assertNotNull(q);
        b.setFrozen(true);
        before = b.toJson();
        assertFalse(b.confirmHardExchange(player, q).success());
        assertEquals(before, b.toJson());
    }

}
