package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.instruments.PresentedCheck;
import java.math.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArcadeBillTest {
    private static String ref(){return "arcadehall_"+UUID.randomUUID();}
    private static BigDecimal decimal(String n){return new BigDecimal(n);}
    private static void same(String n,BigDecimal actual){assertEquals(0,decimal(n).compareTo(actual));}
    @Test void oneBankCommitConsumesTheBillAndCreditsOnlyChange() {
        var b=BankBooks.create(BankConfig.defaults());
        var issuer=UUID.randomUUID();var player=UUID.randomUUID();var stranger=UUID.randomUUID();
        var made=b.issueCheck(issuer,"note",BigInteger.valueOf(25),"中央银行",player,false);
        assertTrue(made.success(),made.message());
        var bill=new PresentedCheck(made.instrumentId(),"note",BigInteger.valueOf(25),issuer,player);
        var wrongFace=new PresentedCheck(made.instrumentId(),"note",BigInteger.valueOf(26),issuer,player);
        assertFalse(b.quoteExternalBill(stranger,bill).get("ok").getAsBoolean());
        assertFalse(b.quoteExternalBill(player,wrongFace).get("ok").getAsBoolean());
        assertTrue(b.quoteExternalBill(player,bill).get("ok").getAsBoolean());
        assertFalse(b.payExternalBill("arcadehall",player,bill,decimal("26"),ref()).success());
        String receipt=ref();
        assertTrue(b.payExternalBill("arcadehall",player,bill,decimal("20"),receipt).success());
        b=BankBooks.fromJson(b.toJson(),BankConfig.defaults());
        assertTrue(b.payExternalBill("arcadehall",player,bill,decimal("20.00"),receipt).success());
        assertFalse(b.payExternalBill("arcadehall",player,bill,decimal("19"),receipt).success());
        assertFalse(b.payExternalBill("arcadehall",player,bill,decimal("20"),ref()).success());
        assertFalse(b.redeemCheck(player,bill).success());
        same("5",b.balanceExact(player,"note"));
        same("25",b.centralIssuedExact());
        assertTrue(b.externalTransferRecorded("arcadehall",receipt));
        assertEquals(1,b.externalSpending(player).size());
        BigDecimal treasury=b.externalTreasuryQuote();
        assertFalse(b.moveExternalFunds("arcadehall",issuer,false,decimal("20.0001"),treasury,ref()).success());
        assertTrue(b.moveExternalFunds("arcadehall",issuer,false,decimal("20"),treasury,ref()).success());
        assertEquals(0,treasury.add(decimal("20")).compareTo(b.externalTreasuryQuote()));
        b.checkInvariants();
    }
}
