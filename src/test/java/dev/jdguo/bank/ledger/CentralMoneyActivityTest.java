package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.instruments.PresentedCheck;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CentralMoneyActivityTest {
    private static String ref(String partner) { return partner + "_" + UUID.randomUUID(); }
    private static BigInteger n(long amount) { return BigInteger.valueOf(amount); }
    private static void equal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), actual.toPlainString());
    }

    @Test void previousSnapshotAndFutureExternalServicesCountOnceInM0() {
        var config = BankConfig.defaults();
        var b = BankBooks.create(config);
        UUID id = UUID.randomUUID();
        assertTrue(b.creditUnbacked(id,"note",n(1000)).success());
        for (String partner : new String[]{"arcadehall", "future_service"}) {
            assertTrue(b.moveExternalFunds(partner,id,true,new BigDecimal("110.1250"),b.externalTreasuryQuote(),ref(partner)).success());
        }
        equal("220.2500", b.centralM0Exact()); equal("220.2500", b.centralM1Exact());
        equal("0", b.centralDepositsExact());
        var m2 = b.centralM2();
        assertTrue(b.prepareExternalInstrument("arcadehall",id,n(20),"cash",ref("arcadehall")).success());
        equal("220.2500", b.centralM0Exact());
        assertTrue(b.creditFromExternal("future_service",id,new BigDecimal("10.25"),ref("future_service")).success());
        equal("210", b.centralM0Exact()); equal("10.25", b.centralDepositsExact());
        assertTrue(b.issueCentralPaper(id,n(10)).result().success());
        equal("220", b.centralM0Exact()); equal("220.2500", b.centralM1Exact());
        assertEquals(0, m2.compareTo(b.centralM2()));
        assertTrue(b.moveExternalFunds("future_service",id,false,new BigDecimal("1.125"),b.externalTreasuryQuote(),ref("future_service")).success());
        equal("218.875", b.centralM0Exact()); equal("219.125", b.centralM1Exact());
        String serialized = b.toJson();
        b = BankBooks.fromJson(serialized,config);
        equal("218.875", b.centralM0Exact()); equal("219.125", b.centralM1Exact());
        assertEquals(serialized,b.toJson());
        b.checkInvariants();
    }

    @Test void outstandingTotalsAndTimelineIncludeOnlyOneEntryPerBillAction() {
        var config = BankConfig.defaults();
        var b = BankBooks.create(config);
        UUID id = UUID.randomUUID();
        assertTrue(b.deposit(id,"diamond",n(30)).success());
        assertTrue(b.withdraw(id,"diamond",n(5),false).success());
        assertFalse(b.withdraw(id,"diamond",n(1000),false).success());
        assertTrue(b.creditUnbacked(id,"note",n(300)).success());
        assertTrue(b.moveExternalFunds("arcadehall",id,true,new BigDecimal("100"),b.externalTreasuryQuote(),ref("arcadehall")).success());
        assertTrue(b.prepareExternalInstrument("arcadehall",id,n(30),"bill",ref("arcadehall")).success());
        assertTrue(b.creditFromExternal("arcadehall",id,new BigDecimal("20"),ref("arcadehall")).success());
        assertTrue(b.issueCheck(id,"note",n(10),"Player",null,true).success());
        assertTrue(b.issueCheck(id,"note",n(15),"Player",null,false).success());
        assertEquals(new BankBooks.OutstandingBills(n(55),3,n(45),2),b.outstandingCentralBills());
        var check = b.check(UUID.fromString(b.nextExternalInstrument("arcadehall",id).get("instrument").getAsString()));
        var presented = new PresentedCheck(check.id(),check.currency(),check.amount(),check.issuer(),check.payee());
        assertTrue(b.redeemCheck(id,presented).success());
        assertFalse(b.redeemCheck(id,presented).success());
        b = BankBooks.fromJson(b.toJson(),config);
        assertEquals(new BankBooks.OutstandingBills(n(25),2,n(15),1),b.outstandingCentralBills());
        assertEquals(1,b.adminActivity().stream().filter(e -> "billRedeem".equals(e.action())).count());
        assertEquals(2,b.adminActivity().stream().filter(e -> "deposit".equals(e.action()) || "withdraw".equals(e.action())).count());
    }
}
