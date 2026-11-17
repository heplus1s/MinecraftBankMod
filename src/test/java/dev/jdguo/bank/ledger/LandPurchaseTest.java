package dev.jdguo.bank.ledger;
import dev.jdguo.bank.currency.BankConfig;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class LandPurchaseTest {
    final UUID payer = UUID.randomUUID();
    BankBooks funded() {
        BankBooks b = BankBooks.create(BankConfig.defaults());
        assertTrue(b.deposit(payer,"diamond",BigInteger.valueOf(100)).success());
        assertTrue(b.creditHeld(payer,"note",new BigDecimal("1000")).success()); return b;
    }
    @Test void wholePhysicalTenderRemainderAndRestartRefund() {
        BankBooks b=funded(); var q=b.quoteLandPurchase("note",BigInteger.valueOf(7),"diamond");
        assertTrue(new BigDecimal(q.get("quantity").getAsString()).signum()>0);
        assertTrue(new BigDecimal(q.get("noteTopup").getAsString()).signum()>0); assertTrue(q.get("success").getAsBoolean());
        BigDecimal quantity=new BigDecimal(q.get("quantity").getAsString()); assertTrue(quantity.scale()<=0);
        var before=b.toJson();
        assertTrue(b.payLandPurchase(payer,"note",BigInteger.valueOf(7),"diamond",q.get("fingerprint").getAsString(),"market-pay:test").success());
        b.checkInvariants(); assertEquals(Money.ZERO,b.systemAvailableBalance("land","note"));
        b=BankBooks.fromJson(b.toJson(),BankConfig.defaults());
        assertTrue(b.reverseLandPurchase(payer,"note",BigInteger.valueOf(7),"market-pay:test").success());
        assertTrue(b.reverseLandPurchase(payer,"note",BigInteger.valueOf(7),"market-pay:test").success());
        b.checkInvariants(); assertEquals(new BigDecimal("100.0000"),b.balanceExact(payer,"diamond"));
        assertEquals(new BigDecimal("1000.0000"),b.balanceExact(payer,"note"));
    }
    @Test void idempotentChargeWrongQuoteAndCompletedRefundRejected() {
        BankBooks b=funded();var q=b.quoteLandPurchase("note",BigInteger.valueOf(5),"note");String fp=q.get("fingerprint").getAsString();
        assertFalse(b.payLandPurchase(payer,"note",BigInteger.valueOf(5),"note",fp+"bad","a").success());
        assertTrue(b.payLandPurchase(payer,"note",BigInteger.valueOf(5),"note",fp,"a").success());
        assertTrue(b.payLandPurchase(payer,"note",BigInteger.valueOf(5),"note",fp,"a").success());
        assertEquals(new BigDecimal("995.0000"),b.balanceExact(payer,"note"));
        assertFalse(b.reverseLandPurchase(payer,"diamond",BigInteger.valueOf(5),"a").success());
        assertTrue(b.completeSystemPayment("a").success());
        assertFalse(b.reverseLandPurchase(payer,"note",BigInteger.valueOf(5),"a").success());
        assertEquals(new BigDecimal("5.0000"),b.systemAvailableBalance("land","note")); b.checkInvariants();
    }
    @Test void insufficientRemainderNeverDebitsPhysicalLeg() {
        BankBooks b=funded(); assertTrue(b.transferSavings(payer,UUID.randomUUID(),"note",BigInteger.valueOf(1000)).success());
        var q=b.quoteLandPurchase("note",BigInteger.valueOf(7),"diamond"); String before=b.toJson();
        assertFalse(b.payLandPurchase(payer,"note",BigInteger.valueOf(7),"diamond",q.get("fingerprint").getAsString(),"short").success());
        assertEquals(before,b.toJson());
    }
    @Test void personalCollateralReceiptSurvivesRestartAndRefund() {
        BankBooks b=funded();
        var coin=RecursiveClearingRegression.coin(b,payer,"地币","diamond",100);
        assertTrue(b.depositPersonalReserveExact(payer,new BigDecimal("10")).success());
        String tender="personal:"+coin.id(); var q=b.quoteLandPurchase("note",BigInteger.ONE,tender);
        assertTrue(q.get("success").getAsBoolean(),q.toString());
        BigDecimal held=b.personalHoldingExact(coin.id(),payer), backing=b.effectiveBackingExact(coin);
        assertTrue(b.payLandPurchase(payer,"note",BigInteger.ONE,tender,q.get("fingerprint").getAsString(),"nested").success());
        assertTrue(b.noteHasPending(coin.id())); b.checkInvariants();
        b=BankBooks.fromJson(b.toJson(),BankConfig.defaults());
        assertTrue(b.reverseLandPurchase(payer,"note",BigInteger.ONE,"nested").success());
        assertEquals(held,b.personalHoldingExact(coin.id(),payer)); assertEquals(backing,b.effectiveBackingExact(b.personalNote(payer))); b.checkInvariants();
    }
}
