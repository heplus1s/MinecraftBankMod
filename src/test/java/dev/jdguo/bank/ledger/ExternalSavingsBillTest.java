package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import java.math.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalSavingsBillTest {
    @Test void savingsBillOnlyDebitsConfirmedOwnerAndKeepsNamedDeliveryAfterRestart(){
        var b=BankBooks.create(BankConfig.defaults());
        var owner=UUID.randomUUID();var payee=UUID.randomUUID();
        String scope="public_test", ref=scope+"_"+UUID.randomUUID();
        assertTrue(b.creditUnbacked(owner,"note",BigInteger.valueOf(100)).success());
        assertTrue(b.transferTreasury(owner,"note",new BigDecimal("25"),false).success());
        assertTrue(b.prepareExternalSavingsBill(scope,owner,payee,BigInteger.TEN,ref).success());
        b=BankBooks.fromJson(b.toJson(),BankConfig.defaults());
        assertTrue(b.prepareExternalSavingsBill(scope,owner,payee,BigInteger.TEN,ref).success());
        assertFalse(b.prepareExternalSavingsBill(scope,owner,UUID.randomUUID(),BigInteger.TEN,ref).success());
        assertFalse(b.prepareExternalSavingsBill(scope,owner,payee,BigInteger.valueOf(16),scope+"_"+UUID.randomUUID()).success());
        assertEquals(0,b.balanceExact(owner,"note").compareTo(new BigDecimal("15")));
        var pending=b.nextExternalInstrument(scope,payee);
        assertNotNull(pending);assertNull(b.nextExternalInstrument(scope,owner));
        var bill=b.check(UUID.fromString(pending.get("instrument").getAsString()));
        assertEquals(payee,bill.payee());assertEquals(owner,bill.issuer());assertEquals("中央银行",bill.issuerName());
        assertFalse(b.finishExternalInstrument(scope,owner,ref).success());
        assertTrue(b.finishExternalInstrument(scope,payee,ref).success());
        assertTrue(b.externalTransferRecorded(scope,ref));
        b.checkInvariants();
    }
}
