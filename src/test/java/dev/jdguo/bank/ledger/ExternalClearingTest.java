package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.NoteRegime;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ExternalClearingTest {
    private final UUID owner=UUID.randomUUID(), customer=UUID.randomUUID();
    private static final String MOD="arcadehall";
    private static BigDecimal d(String n){return new BigDecimal(n);}
    private static void eq(String expected,BigDecimal actual){assertEquals(0,d(expected).compareTo(actual));}
    private static void ok(TxResult r){assertTrue(r.success(),r.code()+": "+r.message());}
    private static String ref(){return MOD+"_"+UUID.randomUUID();}
    private static BankBooks bank(){
        BankBooks bank=BankBooks.create(BankConfig.defaults());
        for(String currency:new String[]{"note","copper","diamond"})bank.setAlpha(currency,BigDecimal.ZERO);
        bank.setReserve("diamond",BigInteger.valueOf(1000));
        ok(bank.creditUnbacked(UUID.randomUUID(),"note",BigInteger.valueOf(1000)));
        return bank;
    }
    @Test void externalPaymentExchangeAndRecallsDoNotMigrateIssuedTotal(){
        var b=bank();
        ok(b.transferTreasury(customer,"note",d("100"),false));
        String buy=ref();
        var q=b.quoteExternalPurchase(customer,"note",d("40"));
        assertTrue(q.get("ok").getAsBoolean(),q.toString());eq("40",q.get("noteValue").getAsBigDecimal());
        String fingerprint=q.get("fingerprint").getAsString();
        ok(b.payExternalPurchase(MOD,customer,"note",d("40"),fingerprint,buy));
        ok(b.payExternalPurchase(MOD,customer,"note",d("40"),fingerprint,buy));
        eq("60",b.balanceExact(customer,"note"));eq("0",b.systemSettlementBalance(MOD,"note"));
        assertEquals(1,b.externalSpending(customer).size());assertEquals(0,b.externalSpending(owner).size());
        ok(b.moveExternalFunds(MOD,owner,true,d("80"),b.externalTreasuryQuote(),ref()));
        eq("820",b.externalTreasuryQuote());eq("180",b.centralM1Exact());
        ok(b.moveExternalFunds(MOD,owner,false,d("20"),b.externalTreasuryQuote(),ref()));
        eq("840",b.externalTreasuryQuote());eq("160",b.centralM1Exact());
        assertFalse(b.creditFromExternal("othermod",customer,d("1"),"othermod_"+UUID.randomUUID()).success());
        String redeem=ref();ok(b.creditFromExternal(MOD,customer,d("10"),redeem));ok(b.creditFromExternal(MOD,customer,d("10"),redeem));
        eq("70",b.balanceExact(customer,"note"));eq("1000",b.centralIssuedExact());
        b=BankBooks.fromJson(b.toJson(),BankConfig.defaults());
        assertTrue(b.externalTransferRecorded(MOD,buy));assertFalse(b.externalTransferRecorded("other",buy));
        eq("1000",b.centralIssuedExact());eq("160",b.centralM1Exact());b.checkInvariants();
    }
    @Test void personalPurchaseClearsRealCollateralRatherThanFaceValue(){
        var b=bank();
        ok(b.transferTreasury(owner,"note",d("25"),false));
        assertTrue(b.createPersonalNote(owner,"测试币",NoteRegime.FLOAT,"diamond",BigInteger.ONE).success());
        ok(b.switchPersonalReserve(owner,"note"));ok(b.depositPersonalReserveExact(owner,d("3")));
        assertTrue(b.issuePersonalNoteUnbacked(owner,BigInteger.TEN).success());
        var id=b.personalNote(owner).id();String coin="personal:"+id;
        ok(b.transferPersonalExact(owner,customer,coin,d("5")));
        var q=b.quoteExternalPurchase(customer,coin,d("5"));
        assertTrue(q.get("ok").getAsBoolean(),q.toString());eq("1.5",q.get("settled").getAsBigDecimal());
        ok(b.payExternalPurchase(MOD,customer,coin,d("5"),q.get("fingerprint").getAsString(),ref()));
        eq("0",b.personalHoldingExact(id,customer));eq("5",b.personalRecovered(id));eq("1.5",b.centralPledgedNotesExact());
        eq("0",b.systemSettlementBalance(MOD,"note"));eq("1000",b.centralIssuedExact());b.checkInvariants();
    }
    @Test void changedQuoteOrWrongNamespaceCannotDebit(){
        var b=bank();ok(b.transferTreasury(customer,"note",d("15"),false));
        var q=b.quoteExternalPurchase(customer,"note",d("10"));
        assertFalse(b.payExternalPurchase(MOD,customer,"note",d("11"),q.get("fingerprint").getAsString(),ref()).success());
        assertFalse(b.payExternalPurchase("other",customer,"note",d("10"),q.get("fingerprint").getAsString(),ref()).success());
        eq("15",b.balanceExact(customer,"note"));assertTrue(b.externalSpending(customer).isEmpty());
    }
    @Test void paperAndNamedBillUseOneReceiptAndNeverCreditSavings(){
        var b=bank();
        ok(b.moveExternalFunds(MOD,owner,true,d("100"),b.externalTreasuryQuote(),ref()));
        var cash=ref();var bill=ref();
        ok(b.prepareExternalInstrument(MOD,customer,BigInteger.valueOf(20),"cash",cash));
        ok(b.prepareExternalInstrument(MOD,customer,BigInteger.valueOf(20),"cash",cash));
        assertFalse(b.prepareExternalInstrument(MOD,customer,BigInteger.valueOf(20),"bill",cash).success());
        ok(b.prepareExternalInstrument(MOD,customer,BigInteger.valueOf(30),"bill",bill));
        eq("0",b.balanceExact(customer,"note"));eq("1000",b.centralIssuedExact());eq("100",b.centralM1Exact());
        assertEquals(BigInteger.ONE,b.paperOutstanding("note",null,BigInteger.valueOf(20)));
        assertNull(b.nextExternalInstrument(MOD,owner));
        assertEquals("cash",b.nextExternalInstrument(MOD,customer).get("mode").getAsString());
        assertFalse(b.finishExternalInstrument(MOD,owner,cash).success());ok(b.finishExternalInstrument(MOD,customer,cash));
        var q=b.nextExternalInstrument(MOD,customer);assertEquals("bill",q.get("mode").getAsString());
        UUID number=UUID.fromString(q.get("instrument").getAsString());
        assertEquals(customer,b.check(number).payee());assertEquals(BigInteger.valueOf(30),b.check(number).amount());
        b=BankBooks.fromJson(b.toJson(),BankConfig.defaults());assertEquals(number.toString(),b.nextExternalInstrument(MOD,customer).get("instrument").getAsString());
        ok(b.finishExternalInstrument(MOD,customer,bill));assertNull(b.nextExternalInstrument(MOD,customer));b.checkInvariants();
    }
}
