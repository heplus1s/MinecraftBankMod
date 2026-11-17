package dev.jdguo.bank.ledger;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.NoteRegime;
import java.math.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PersonalPaymentTest {
    final UUID issuer=UUID.randomUUID(), buyer=UUID.randomUUID(), donor=UUID.randomUUID();
    BankBooks setup(boolean fund) {
        BankBooks b=BankBooks.create(BankConfig.defaults());
        b.creditHeld(donor,"note",new BigDecimal("2000"));
        if(fund) assertTrue(b.transferTreasury(donor,"note",new BigDecimal("2000"),true).success());
        b.createPersonalNote(issuer,"货币",NoteRegime.FLOAT,"diamond",BigInteger.ONE);
        b.issuePersonalNoteUnbacked(issuer,BigInteger.TEN);
        b.deposit(issuer,"diamond",BigInteger.ONE);
        b.depositPersonalReserveExact(issuer,BigDecimal.ONE);
        b.deposit(buyer,"diamond",BigInteger.TEN); b.deposit(buyer,"copper",BigInteger.valueOf(10000));
        return b;
    }
    void eq(BigDecimal a,BigDecimal b){assertEquals(0,a.compareTo(b));}
    @Test void physicalPurchaseIsWholeFundedConservedAndDurable(){
        for(String tender:new String[]{"diamond","copper"}){
            BankBooks b=setup(true);UUID coin=b.personalNote(issuer).id();
            var q=b.quotePersonalPayment(buyer,coin,BigInteger.ONE,tender);assertTrue(q.success(),q.code());
            BigDecimal before=b.balanceExact(buyer,tender), liability=b.supply("note").liabilityExact();
            assertTrue(b.buyPersonalPayment(buyer,coin,BigInteger.ONE,tender,q.total(),q.physical(),q.change()).success());
            eq(before.subtract(new BigDecimal(q.physical())),b.balanceExact(buyer,tender));
            eq(q.total(),b.balanceExact(issuer,"note"));eq(q.change(),b.balanceExact(buyer,"note"));
            eq(new BigDecimal("2000").subtract(q.total()).subtract(q.change()),b.treasuryExact("note"));
            eq(liability,b.supply("note").liabilityExact());eq(BigDecimal.ONE,b.personalHoldingExact(coin,buyer));
            b.checkInvariants();BankBooks.fromJson(b.toJson(),BankConfig.defaults()).checkInvariants();
        }
    }
    @Test void treasuryCannotSpendDepositorNotesAndStaleQuoteDoesNothing(){
        BankBooks b=setup(false);UUID coin=b.personalNote(issuer).id();String before=b.toJson();
        var q=b.quotePersonalPayment(buyer,coin,BigInteger.ONE,"diamond");assertEquals("insufficient_treasury",q.code());
        assertFalse(b.buyPersonalPayment(buyer,coin,BigInteger.ONE,"diamond",q.total(),q.physical(),q.change()).success());assertEquals(before,b.toJson());
        b=setup(true);coin=b.personalNote(issuer).id();q=b.quotePersonalPayment(buyer,coin,BigInteger.ONE,"diamond");before=b.toJson();
        assertFalse(b.buyPersonalPayment(buyer,coin,BigInteger.ONE,"diamond",q.total(),q.physical().add(BigInteger.ONE),q.change()).success());assertEquals(before,b.toJson());
    }
    @Test void unsupportedTenderAndSelfPurchaseAreRejected(){
        BankBooks b=setup(true);UUID coin=b.personalNote(issuer).id();
        assertFalse(b.quotePersonalPayment(buyer,coin,BigInteger.ONE,"fake").success());
        assertFalse(b.quotePersonalPayment(issuer,coin,BigInteger.ONE,"diamond").success());
        assertFalse(b.quotePersonalPayment(buyer,coin,BigInteger.valueOf(11),"diamond").success());
    }
    @Test void centralPaymentNeedsNoTreasuryAndPaysSellerOnlyOncePerInvocation(){
        BankBooks b=setup(false);UUID coin=b.personalNote(issuer).id();b.creditHeld(buyer,"note",BigDecimal.TEN);
        var q=b.quotePersonalPayment(buyer,coin,BigInteger.ONE,"note");assertTrue(q.success(),q.code());
        assertTrue(b.buyPersonalPayment(buyer,coin,BigInteger.ONE,"note",q.total(),q.physical(),q.change()).success());
        eq(q.total(),b.balanceExact(issuer,"note"));eq(BigDecimal.ZERO,b.treasuryExact("note"));b.checkInvariants();
    }
}
