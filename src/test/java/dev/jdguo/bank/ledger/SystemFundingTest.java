package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SystemFundingTest {
    private final UUID admin = UUID.randomUUID(), customer = UUID.randomUUID();
    private static BigDecimal n(String s) { return new BigDecimal(s); }
    private static void eq(String s, BigDecimal value) { assertEquals(0, n(s).compareTo(value)); }
    private BankBooks funded() {
        BankBooks b = BankBooks.create(BankConfig.defaults());
        b.creditHeld(admin, "note", n("20"));
        assertTrue(b.transferTreasury(admin, "note", n("20"), true).success());
        b.deposit(customer, "diamond", BigInteger.TEN);
        return b;
    }
    @Test void transfersOnlyExistingTreasuryNotesToIndependentAccountsAndSurvivesRestart() {
        BankBooks b = funded();
        BigDecimal liability = b.supply("note").liabilityExact();
        assertTrue(b.fundSystemAccount(admin, true, "shop", n("3.125"), n("20"), n("0")).success());
        assertTrue(b.fundSystemAccount(admin, true, "land", n("4.25"), n("16.875"), n("0")).success());
        eq("12.625", b.treasuryExact("note")); eq("3.125", b.systemFundingBalance("shop"));
        eq("4.25", b.systemFundingBalance("land")); eq("10", b.balanceExact(customer, "diamond"));
        eq("10", b.custodyExact("diamond")); assertEquals(liability, b.supply("note").liabilityExact());
        b.checkInvariants();
        BankBooks restored = BankBooks.fromJson(b.toJson(), BankConfig.defaults());
        eq("12.625", restored.treasuryExact("note")); eq("3.125", restored.systemFundingBalance("shop"));
        eq("4.25", restored.systemFundingBalance("land")); assertEquals(2, restored.systemFundingLog().size());
        restored.checkInvariants();
    }
    @Test void unauthorizedInsufficientInvalidAndStaleTransfersAreNoOps() {
        BankBooks b = funded(); String before = b.toJson();
        assertFalse(b.fundSystemAccount(admin, false, "shop", n("1"), n("20"), n("0")).success());
        assertFalse(b.fundSystemAccount(admin, true, "player", n("1"), n("20"), n("0")).success());
        assertFalse(b.fundSystemAccount(admin, true, "shop", n("21"), n("20"), n("0")).success());
        assertFalse(b.fundSystemAccount(admin, true, "shop", n("0.00001"), n("20"), n("0")).success());
        assertFalse(b.fundSystemAccount(admin, true, "shop", n("-1"), n("20"), n("0")).success());
        assertFalse(b.fundSystemAccount(admin, true, "shop", n("1"), n("19"), n("0")).success());
        assertEquals(before, b.toJson());
        assertTrue(b.fundSystemAccount(admin, true, "shop", n("1"), n("20"), n("0")).success());
        before = b.toJson();
        assertFalse(b.fundSystemAccount(admin, true, "shop", n("1"), n("20"), n("0")).success());
        assertEquals(before, b.toJson());
    }
    @Test void playerCustodyIsNotFundingSource() {
        BankBooks b = BankBooks.create(BankConfig.defaults());
        b.creditHeld(customer, "note", n("100")); String before = b.toJson();
        assertFalse(b.fundSystemAccount(admin, true, "shop", n("1"), n("0"), n("0")).success());
        assertEquals(before, b.toJson());
    }
}
