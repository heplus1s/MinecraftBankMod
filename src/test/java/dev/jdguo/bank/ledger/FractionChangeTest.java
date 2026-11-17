package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.NoteRegime;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FractionChangeTest {
    final UUID issuer = UUID.randomUUID(), admin = UUID.randomUUID();
    static BigDecimal n(String value) { return new BigDecimal(value); }
    static void eq(String expected, BigDecimal actual) { assertEquals(0,n(expected).compareTo(actual), actual.toPlainString()); }
    BankBooks setup(boolean funded) {
        BankBooks b = BankBooks.create(BankConfig.defaults());
        b.setReserve("copper",BigInteger.ZERO);
        b.setReserve("diamond",BigInteger.valueOf(2000));
        assertTrue(b.creditHeld(admin,"note",BigInteger.valueOf(2000)).success());
        if (funded) assertTrue(b.transferTreasury(admin,"note",n("100"),true).success());
        assertTrue(b.deposit(issuer,"diamond",BigInteger.TEN).success());
        assertTrue(b.createPersonalNote(issuer,"测试币",NoteRegime.FLOAT,"diamond",BigInteger.ONE).success());
        assertTrue(b.depositPersonalReserveExact(issuer,n("1.3")).success());
        eq("1",b.unitsPer("note","diamond"));
        return b;
    }
    @Test void switchPaysOnlyNewFractionWithRealTreasuryAndPreservesHistory() {
        BankBooks b=setup(true);
        BigDecimal notes=b.supply("note").liabilityExact(), vault=b.custodyExact("diamond");
        assertTrue(b.switchPersonalReserve(issuer,"copper").success());
        eq("9.7",b.balanceExact(issuer,"diamond")); // historical .7 is not rounded/converted
        eq("0.3",b.balanceExact(issuer,"note")); eq("99.7",b.treasuryExact("note"));
        eq("0.3",b.treasuryExact("diamond"));
        assertEquals(0,vault.subtract(n("0.3")).compareTo(b.custodyExact("diamond")));
        assertEquals(0,notes.compareTo(b.supply("note").liabilityExact()));
        BankBooks restored=BankBooks.fromJson(b.toJson(),BankConfig.defaults());
        eq("9.7",restored.balanceExact(issuer,"diamond")); eq("0.3",restored.balanceExact(issuer,"note"));
        restored.checkInvariants();
    }
    @Test void unfundedTreasuryPreservesTheFullClaim() {
        BankBooks b=setup(false);
        TxResult result=b.switchPersonalReserve(issuer,"copper");
        assertTrue(result.success()); assertTrue(result.message().contains("权益保留"));
        eq("10",b.balanceExact(issuer,"diamond")); eq("0",b.balanceExact(issuer,"note"));
        eq("0",b.treasuryExact("diamond")); b.checkInvariants();
    }
    @Test void noQuotePreservesTheFullClaim() {
        BankBooks b=setup(true); b.setReserve("diamond",BigInteger.ZERO);
        assertTrue(b.switchPersonalReserve(issuer,"copper").success());
        eq("10",b.balanceExact(issuer,"diamond")); eq("100",b.treasuryExact("note"));
    }
    @Test void unrepresentableValueDoesNotRoundOrStealDust() {
        BankBooks b=setup(true); b.setReserve("diamond",BigInteger.valueOf(4000));
        eq("0.3333",b.unitsPer("note","diamond")); // .3 * .3333 = .09999
        assertTrue(b.switchPersonalReserve(issuer,"copper").success());
        eq("10",b.balanceExact(issuer,"diamond")); eq("100",b.treasuryExact("note"));
    }
    @Test void reserveWithdrawalAlsoUsesFundedChange() {
        BankBooks b=setup(true);
        // Legacy unlocked FLOAT collateral is protected; explicit replacement is tested above.
        b.personalNote(issuer).adopt(NoteRegime.PEG,"diamond",null);
        assertTrue(b.withdrawPersonalReserveExact(issuer,n("0.3")).success());
        eq("8.7",b.balanceExact(issuer,"diamond")); eq("0.3",b.balanceExact(issuer,"note"));
        eq("1",b.effectiveBackingExact(b.personalNote(issuer))); b.checkInvariants();
    }
    @Test void centralCheckUsesPreRedemptionPriceAndCannotPayTwice() {
        BankBooks b=setup(true); b.setReserve("diamond",BigInteger.valueOf(1500));
        eq("0.5",b.unitsPer("diamond","note"));
        TxResult issued=b.issueCheck(admin,"note",BigInteger.ONE,"admin",issuer,true);
        assertTrue(issued.success());
        var paper=new dev.jdguo.bank.instruments.PresentedCheck(issued.instrumentId(),"note",BigInteger.ONE,admin,issuer);
        assertTrue(b.redeemCheck(issuer,paper).success());
        eq("8.7",b.balanceExact(issuer,"diamond")); eq("1",b.balanceExact(issuer,"note"));
        eq("99",b.treasuryExact("note")); eq("0.5",b.treasuryExact("diamond"));
        BankBooks restored=BankBooks.fromJson(b.toJson(),BankConfig.defaults());
        assertFalse(restored.redeemCheck(issuer,paper).success());
        eq("1",restored.balanceExact(issuer,"note")); restored.checkInvariants();
    }
    @Test void personalRedemptionReceiptAndReloadAreIdempotent() {
        BankBooks b=setup(true);
        assertTrue(b.issuePersonalNoteUnbacked(issuer,BigInteger.ONE).success());
        UUID id=b.personalNote(issuer).id();
        assertTrue(b.redeemPersonalToSavings(issuer,id,BigDecimal.ONE,"fraction-redeem").success());
        eq("9.7",b.balanceExact(issuer,"diamond")); eq("0.3",b.balanceExact(issuer,"note"));
        BankBooks restored=BankBooks.fromJson(b.toJson(),BankConfig.defaults());
        String before=restored.toJson();
        assertTrue(restored.redeemPersonalToSavings(issuer,id,BigDecimal.ONE,"fraction-redeem").success());
        assertEquals(before,restored.toJson()); restored.checkInvariants();
    }
    @Test void insufficientNoteTreasuryNeverPaysPartially() {
        BankBooks b=setup(true);
        assertTrue(b.transferTreasury(admin,"note",n("99.9"),false).success());
        assertTrue(b.switchPersonalReserve(issuer,"copper").success());
        eq("10",b.balanceExact(issuer,"diamond")); eq("0",b.balanceExact(issuer,"note"));
        eq("0.1",b.treasuryExact("note")); eq("0",b.treasuryExact("diamond"));
    }
    @Test void failedCheckPayoutDoesNotConsumeTheCheck() {
        BankBooks b=setup(true);
        TxResult issued=b.issueCheck(admin,"note",BigInteger.ONE,"admin",issuer,true);
        var paper=new dev.jdguo.bank.instruments.PresentedCheck(issued.instrumentId(),"note",BigInteger.ONE,admin,issuer);
        b.setReserve("diamond",BigInteger.ZERO);
        String before=b.toJson();
        assertFalse(b.redeemCheck(issuer,paper).success()); assertEquals(before,b.toJson());
        b.setReserve("diamond",BigInteger.valueOf(1500));
        assertTrue(b.redeemCheck(issuer,paper).success());
        eq("1",b.balanceExact(issuer,"note"));
    }
}
