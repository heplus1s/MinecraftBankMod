package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.instruments.CheckRecord;
import dev.jdguo.bank.instruments.PresentedCheck;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalBearerBillTest {
    private static final String MOD = "arcadehall";
    private static final BigInteger FACE = BigInteger.valueOf(1000);

    private static PresentedCheck presented(CheckRecord r) {
        return new PresentedCheck(r.id(),r.currency(),r.amount(),r.issuer(),r.payee());
    }

    @Test void externalBillCanBePassedOnOnceWithoutMovingDiamondReserves(){
        var config=BankConfig.defaults();
        var b=BankBooks.create(config);
        UUID recipient=UUID.randomUUID(), bearer=UUID.randomUUID();
        assertTrue(b.creditUnbacked(recipient,"note",BigInteger.valueOf(10000)).success());
        b.supply("diamond").setReserve(BigInteger.valueOf(10000));
        assertTrue(b.moveExternalFunds(MOD,recipient,true,new BigDecimal(FACE),b.externalTreasuryQuote(),MOD+"_"+UUID.randomUUID()).success());
        String ref=MOD+"_"+UUID.randomUUID();
        assertTrue(b.prepareExternalInstrument(MOD,recipient,FACE,"bill",ref).success());
        assertTrue(b.prepareExternalInstrument(MOD,recipient,FACE,"bill",ref).success());
        assertNull(b.nextExternalInstrument(MOD,bearer));
        assertFalse(b.finishExternalInstrument(MOD,bearer,ref).success());
        var id=UUID.fromString(b.nextExternalInstrument(MOD,recipient).get("instrument").getAsString());
        assertNull(b.check(id).payee());
        assertTrue(b.finishExternalInstrument(MOD,recipient,ref).success());
        b=BankBooks.fromJson(b.toJson(),config);
        assertNull(b.nextExternalInstrument(MOD,recipient));
        assertFalse(b.prepareExternalInstrument(MOD,recipient,BigInteger.ONE,"bill",MOD+"_"+UUID.randomUUID()).success());
        assertTrue(b.quoteExternalBill(bearer,presented(b.check(id))).get("ok").getAsBoolean());
        verifyNoteRedemption(b,bearer,presented(b.check(id)));
        assertFalse(b.redeemCheck(recipient,presented(b.check(id))).success());
    }

    @Test void oldExternalNamedBillStillPaysOnlyOriginalPayeeInNotes(){
        var config=BankConfig.defaults();
        var b=BankBooks.create(config);
        UUID recipient=UUID.randomUUID(), other=UUID.randomUUID();
        assertTrue(b.creditUnbacked(recipient,"note",BigInteger.valueOf(10000)).success());
        b.supply("diamond").setReserve(BigInteger.valueOf(10000));
        assertTrue(b.moveExternalFunds(MOD,recipient,true,new BigDecimal(FACE),b.externalTreasuryQuote(),MOD+"_"+UUID.randomUUID()).success());
        String ref=MOD+"_"+UUID.randomUUID();
        assertTrue(b.prepareExternalInstrument(MOD,recipient,FACE,"bill",ref).success());
        var id=UUID.fromString(b.nextExternalInstrument(MOD,recipient).get("instrument").getAsString());
        assertTrue(b.finishExternalInstrument(MOD,recipient,ref).success());
        // v1.0.27 serialized this same delivery row, with a named payee in the check record/item.
        var saved=com.google.gson.JsonParser.parseString(b.toJson()).getAsJsonObject();
        saved.getAsJsonArray("checks").get(0).getAsJsonObject().addProperty("payee",recipient.toString());
        b=BankBooks.fromJson(saved.toString(),config);
        var ticket=presented(b.check(id));
        assertEquals(recipient,ticket.payee());
        assertEquals("wrong_payee",b.redeemCheck(other,ticket).code());
        verifyNoteRedemption(b,recipient,ticket);
    }

    private static void verifyNoteRedemption(BankBooks b, UUID bearer, PresentedCheck ticket) {
        var issued=b.centralIssuedExact();
        var reserve=b.supply("diamond").reserve();
        var diamonds=b.balanceExact(bearer,"diamond");
        var notes=b.balanceExact(bearer,"note");
        var result=b.redeemCheck(bearer,ticket);
        assertTrue(result.success(),result.message());
        assertEquals(FACE,result.amount());
        assertEquals(0,b.balanceExact(bearer,"note").compareTo(notes.add(new BigDecimal(FACE))));
        assertEquals(0,b.balanceExact(bearer,"diamond").compareTo(diamonds));
        assertEquals(reserve,b.supply("diamond").reserve());
        assertEquals(0,b.centralIssuedExact().compareTo(issued));
        assertEquals("already_redeemed",b.redeemCheck(bearer,ticket).code());
        b.checkInvariants();
    }
}
