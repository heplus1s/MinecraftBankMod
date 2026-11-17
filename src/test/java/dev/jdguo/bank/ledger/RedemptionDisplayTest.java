package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RedemptionDisplayTest {
    @Test void legacyCoverageDoesNotBlockTrueAvailability() {
        BankBooks b=BankBooks.create(BankConfig.defaults());
        UUID player=UUID.randomUUID();
        b.creditUnbacked(player,"note",new BigInteger("1000000"));
        assertTrue(b.tightness("note").compareTo(new BigDecimal("0.05"))<0);
        b.supply("diamond").addReserve(new BigDecimal("500000"));
        b.supply("copper").addReserve(new BigDecimal("500000000"));
        boolean any=b.redemptionDetails().asList().stream().anyMatch(e->e.getAsJsonObject().get("status").getAsString().equals("可兑付"));
        assertTrue(any);
        assertEquals(any,b.statusLabel().startsWith("可兑付（"));
        assertFalse(b.statusLabel().equals("无法兑付央行券"));
    }
    @Test void receiptUsesConfiguredNamesAndExplainsSpread() {
        BankBooks b=BankBooks.create(BankConfig.defaults());UUID player=UUID.randomUUID();
        b.creditUnbacked(player,"note",BigInteger.valueOf(100)); b.deposit(player,"diamond",BigInteger.TEN);
        var q=b.quoteHardExchange("diamond","note",BigInteger.ONE); assertNotNull(q);
        var result=b.confirmHardExchange(player,q);assertTrue(result.success());
        assertTrue(result.message().contains("钻石"));assertTrue(result.message().contains("央行券"));
        assertTrue(result.message().contains("参考价"));assertTrue(result.message().contains("2%"));
        assertTrue(result.message().contains("实收"));assertFalse(result.message().contains("bank note"));
    }
    @Test void personalLandTenderDoesNotRequireCentralNoteTopup() {
        BankBooks b=BankBooks.create(BankConfig.defaults());UUID player=UUID.randomUUID();
        b.deposit(player,"diamond",BigInteger.valueOf(100));
        b.creditUnbacked(player,"note",BigInteger.valueOf(1000));
        b.creditHeld(player,"note",BigDecimal.TEN);
        var coin=RecursiveClearingRegression.coin(b,player,"地币","note",100);
        assertTrue(b.depositPersonalReserveExact(player,new BigDecimal("10")).success());
        var q=b.quoteLandPurchase("note",BigInteger.ONE,"personal:"+coin.id());
        assertTrue(q.get("success").getAsBoolean(),q.toString());
        assertEquals(0,new BigDecimal(q.get("noteTopup").getAsString()).signum());
        assertEquals(0,b.balanceExact(player,"note").signum());
        BigDecimal before=b.personalHoldingExact(coin.id(),player);
        assertTrue(b.payLandPurchase(player,"note",BigInteger.ONE,"personal:"+coin.id(),q.get("fingerprint").getAsString(),"land:test").success());
        assertEquals(0,b.balanceExact(player,"note").signum());
        assertTrue(b.personalHoldingExact(coin.id(),player).compareTo(before)<0);
        assertTrue(b.reverseLandPurchase(player,"note",BigInteger.ONE,"land:test").success());
        assertEquals(before,b.personalHoldingExact(coin.id(),player));b.checkInvariants();
    }
    @Test void inexactPersonalLandQuoteRejectsWithoutCentralNoteFallbackOrMutation() {
        BankBooks b=BankBooks.create(BankConfig.defaults());UUID p=UUID.randomUUID();
        b.deposit(p,"diamond",BigInteger.valueOf(100));b.creditHeld(p,"note",new BigDecimal("1000"));
        RecursiveClearingRegression.coin(b,p,"异币","diamond",100);
        b.depositPersonalReserveExact(p,BigDecimal.TEN);
        String before=b.toJson();
        var q=b.quoteLandPurchase("note",BigInteger.ONE,"personal:"+b.personalNote(p).id());
        assertFalse(q.get("success").getAsBoolean());assertEquals("inexact_tender",q.get("code").getAsString());
        assertEquals(before,b.toJson());
    }
}
