package dev.jdguo.bank.ledger;

import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CentralTreasuryTest {
    private final UUID admin = UUID.randomUUID(), customer = UUID.randomUUID();
    private static BigDecimal n(String s) { return new BigDecimal(s); }
    private static void eq(String expected, BigDecimal actual) { assertEquals(0, n(expected).compareTo(actual)); }
    @Test void customerCustodyCannotBeUsedAsCentralReserves() {
        BankBooks b = BankBooks.create(BankConfig.defaults());
        b.deposit(customer,"diamond", BigInteger.valueOf(12));
        String before = b.toJson();
        assertEquals("insufficient_treasury",b.transferReserve("diamond",n("1"),true).code());
        assertEquals(before,b.toJson());
        eq("12",b.balanceExact(customer,"diamond"));
    }
    @Test void contributionAndReserveRoundTripConserveAssetsAndCustomerClaims() {
        BankBooks b = BankBooks.create(BankConfig.defaults());
        b.deposit(admin,"diamond", BigInteger.TEN);
        b.deposit(customer,"diamond", BigInteger.valueOf(12));
        BigDecimal reserve = b.supply("diamond").reserveExact();
        BigDecimal liability = b.supply("diamond").liabilityExact();
        BigDecimal quote = b.unitsPer("copper", "diamond");
        assertTrue(b.transferTreasury(admin,"diamond",n("5"),true).success());
        eq("17",b.custodyExact("diamond")); eq("5",b.treasuryExact("diamond"));
        assertEquals(0,quote.compareTo(b.unitsPer("copper", "diamond")));
        assertEquals(0,liability.subtract(n("5")).compareTo(b.supply("diamond").liabilityExact()));
        assertTrue(b.transferReserve("diamond",n("3.5"),true).success());
        eq("1.5",b.treasuryExact("diamond"));
        assertEquals(0,reserve.add(n("3.5")).compareTo(b.supply("diamond").reserveExact()));
        assertTrue(b.transferReserve("diamond",n("3.5"),false).success());
        assertTrue(b.transferTreasury(admin,"diamond",n("5"),false).success());
        eq("10",b.balanceExact(admin,"diamond"));eq("12",b.balanceExact(customer,"diamond"));
        eq("22",b.custodyExact("diamond"));eq("0",b.treasuryExact("diamond"));
        assertEquals(0,liability.compareTo(b.supply("diamond").liabilityExact()));
        b.checkInvariants();
    }
    @Test void oldSaveHasZeroTreasuryAndRoundTripPreservesIt() {
        BankBooks b = BankBooks.create(BankConfig.defaults());
        b.deposit(admin,"diamond",BigInteger.TEN);
        var root = JsonParser.parseString(b.toJson()).getAsJsonObject();
        root.remove("centralTreasury");
        BankBooks legacy = BankBooks.fromJson(root.toString(),BankConfig.defaults());
        eq("0",legacy.treasuryExact("diamond"));eq("10",legacy.balanceExact(admin,"diamond"));
        assertTrue(legacy.transferTreasury(admin,"diamond",n("2"),true).success());
        BankBooks restored = BankBooks.fromJson(legacy.toJson(),BankConfig.defaults());
        eq("2",restored.treasuryExact("diamond"));eq("8",restored.balanceExact(admin,"diamond"));
        eq("8",restored.custodyExact("diamond"));
    }
    @Test void fractionalPublicNotesStayExactAndTreasuryIsNotRetirableInventory() {
        BankBooks b = BankBooks.create(BankConfig.defaults());
        b.creditHeld(admin,"note",n("3.125"));
        BigDecimal before = b.centralM1Exact();
        assertTrue(b.transferTreasury(admin,"note",n("1.125"),true).success());
        assertEquals(0,before.subtract(n("1.125")).compareTo(b.centralM1Exact()));
        eq("1.125",b.treasuryExact("note"));
        assertTrue(b.transferTreasury(admin,"note",n("1.125"),false).success());
        assertEquals(0,before.compareTo(b.centralM1Exact()));
        eq("3.125",b.broadMoneyComponentsExact().get("note"));
    }
    @Test void invalidOrFractionalPhysicalContributionsDoNothing() {
        BankBooks b = BankBooks.create(BankConfig.defaults());
        b.deposit(admin,"diamond",BigInteger.TEN);
        String before=b.toJson();
        assertFalse(b.transferTreasury(admin,"diamond",n("0.5"),true).success());
        assertFalse(b.transferTreasury(admin,"diamond",n("11"),true).success());
        assertEquals(before,b.toJson());
        assertFalse(b.transferReserve("note",n("1"),true).success());
    }
    @Test void m2DisplayUsesFractionalCopperQuoteWithoutAnIntegerFloor() {
        BankBooks b = BankBooks.create(BankConfig.defaults());
        b.deposit(admin, "copper", BigInteger.valueOf(1001));
        b.creditHeld(admin, "note", BigInteger.valueOf(1000));
        BigDecimal expected = b.centralM1Exact().add(b.savingsBook("copper")
                .multiply(b.unitsPer("note", "copper"), dev.jdguo.bank.fx.FxMath.MC));
        assertEquals(0, expected.compareTo(b.centralM2()));
    }
}
