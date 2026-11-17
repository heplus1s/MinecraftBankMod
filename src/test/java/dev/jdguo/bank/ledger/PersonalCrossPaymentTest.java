package dev.jdguo.bank.ledger;
import com.google.gson.JsonObject;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.NoteRegime;
import java.math.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PersonalCrossPaymentTest {
    final UUID buyer=UUID.randomUUID(), seller=UUID.randomUUID(), donor=UUID.randomUUID();
    BankBooks setup(boolean funded) {
        BankBooks b=BankBooks.create(BankConfig.defaults());
        b.creditHeld(donor,"note",new BigDecimal("2000"));
        if(funded) assertTrue(b.transferTreasury(donor,"note",new BigDecimal("2000"),true).success());
        for(UUID owner:new UUID[]{buyer,seller}) {
            assertTrue(b.createPersonalNote(owner,owner.equals(buyer)?"A":"B",NoteRegime.FLOAT,"diamond",BigInteger.ONE).success());
            assertTrue(b.issuePersonalNoteUnbacked(owner,BigInteger.TEN).success());
            b.deposit(owner,"diamond",BigInteger.TEN);
            assertTrue(b.depositPersonalReserveExact(owner,BigDecimal.TEN).success());
        }
        return b;
    }
    JsonObject quote(BankBooks b) {
        JsonObject q=b.quotePersonalCross(buyer,b.personalNote(seller).id(),BigInteger.ONE,"personal:"+b.personalNote(buyer).id());
        q.addProperty("nonce",UUID.randomUUID().toString()); q.addProperty("issuedAt",System.currentTimeMillis()); return q;
    }
    TxResult buy(BankBooks b,JsonObject q){return b.buyPersonalCross(buyer,b.personalNote(seller).id(),BigInteger.ONE,"personal:"+b.personalNote(buyer).id(),q);}
    void eq(BigDecimal a,BigDecimal b){assertEquals(0,a.compareTo(b));}
    @Test void realFundingRecoveryTargetReserveAndDurableIdempotency(){
        BankBooks b=setup(true); JsonObject q=quote(b); assertTrue(q.get("ok").getAsBoolean(),q.toString());
        BigDecimal targetReserve=b.effectiveBackingExact(b.personalNote(seller));
        BigDecimal liability=b.supply("note").liabilityExact();
        assertTrue(buy(b,q).success());
        eq(BigDecimal.ONE,b.personalHoldingExact(b.personalNote(seller).id(),buyer));
        eq(targetReserve,b.effectiveBackingExact(b.personalNote(seller)));
        eq(new BigDecimal(q.get("paymentAmount").getAsString()),b.personalRecovered(b.personalNote(buyer).id()));
        eq(new BigDecimal(q.get("maximum").getAsString()),b.balanceExact(seller,"note"));
        eq(liability,b.supply("note").liabilityExact()); b.checkInvariants();
        b=BankBooks.fromJson(b.toJson(),BankConfig.defaults()); String before=b.toJson();
        assertTrue(buy(b,q).success()); assertEquals(before,b.toJson()); b.checkInvariants();
    }
    @Test void insufficientTreasuryCannotUseDepositorFunding(){
        BankBooks b=setup(false);String before=b.toJson();JsonObject q=quote(b);
        assertEquals("insufficient_treasury",q.get("code").getAsString());assertFalse(buy(b,q).success());assertEquals(before,b.toJson());
    }
    @Test void staleOrAlteredQuoteDoesNotMutate(){
        BankBooks b=setup(true);JsonObject q=quote(b);q.addProperty("paymentAmount","0");String before=b.toJson();
        assertFalse(buy(b,q).success());assertEquals(before,b.toJson());
    }
    @Test void sameCurrencyAndPausedSourceRejected(){
        BankBooks b=setup(true); UUID a=b.personalNote(buyer).id();
        assertFalse(b.quotePersonalCross(buyer,a,BigInteger.ONE,"personal:"+a).get("ok").getAsBoolean());
        assertTrue(b.switchPersonalReserve(buyer,"copper").success());assertFalse(quote(b).get("ok").getAsBoolean());
    }
    @Test void nestedDifferentCollateralClearsEveryLegAndRejectsPausedChain(){
        BankBooks b=setup(true);UUID nested=UUID.randomUUID();
        assertTrue(b.createPersonalNote(nested,"C",NoteRegime.FLOAT,"copper",BigInteger.ONE).success());
        assertTrue(b.issuePersonalNoteUnbacked(nested,BigInteger.valueOf(100)).success());
        b.deposit(nested,"copper",BigInteger.valueOf(10000));
        assertTrue(b.depositPersonalReserveExact(nested,new BigDecimal("10000")).success());
        String anchor="personal:"+b.personalNote(nested).id();
        assertTrue(b.switchPersonalReserve(buyer,anchor).success());
        assertTrue(b.transferPersonalExact(nested,buyer,anchor,new BigDecimal("100")).success());
        assertTrue(b.depositPersonalReserveExact(buyer,new BigDecimal("100")).success());
        JsonObject q=quote(b);assertTrue(q.get("ok").getAsBoolean(),q.toString());
        assertTrue(buy(b,q).success());assertTrue(b.personalRecovered(b.personalNote(nested).id()).signum()>0);
        b.checkInvariants();
        b.setPersonalRedemptionPaused(nested,true);
        assertEquals("paused",quote(b).get("code").getAsString());
    }
}
