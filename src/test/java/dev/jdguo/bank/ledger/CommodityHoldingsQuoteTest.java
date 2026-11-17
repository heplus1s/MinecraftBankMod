package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CommodityHoldingsQuoteTest {
    private static BigDecimal d(String s) { return new BigDecimal(s); }
    private static void eq(String value, BigDecimal actual) { assertNotNull(actual); assertEquals(0,d(value).compareTo(actual)); }
    private BankBooks empty() {
        BankBooks b=BankBooks.create(BankConfig.defaults());
        b.supply("copper").setReserve(BigInteger.ZERO); b.supply("diamond").setReserve(BigInteger.ZERO);
        return b;
    }
    @Test void noCopperReserveStillPricesCustodyButCannotRedeemCustody() {
        BankBooks b=empty(); UUID p=UUID.randomUUID();
        b.deposit(p,"copper",BigInteger.valueOf(100000)); b.supply("diamond").setReserve(BigInteger.valueOf(1000));
        b.creditUnbacked(p,"note",BigInteger.valueOf(1000));
        eq("100",b.unitsPer("copper","diamond")); eq("1",b.unitsPer("diamond","note"));
        b.transferTreasury(p,"note",BigDecimal.ONE,false);
        var quote=b.quoteHardExchange("note","copper",BigInteger.ONE);assertNotNull(quote);
        String before=b.toJson();assertEquals("insufficient_reserves",b.confirmHardExchange(p,quote).code());
        assertEquals(before,b.toJson()); eq("100000",b.balanceExact(p,"copper"));
    }
    @Test void noDiamondReserveStillPricesDiamondCustodyAndCopperBacking() {
        BankBooks b=empty();UUID p=UUID.randomUUID();
        b.supply("copper").setReserve(BigInteger.valueOf(100000));b.deposit(p,"diamond",BigInteger.valueOf(1000));
        b.creditUnbacked(p,"note",BigInteger.valueOf(1000));
        eq("100",b.unitsPer("copper","diamond"));eq("1",b.unitsPer("diamond","note"));
        b.transferTreasury(p,"note",d("10"),false);
        var q=b.quoteHardExchange("note","diamond",BigInteger.ONE);assertNotNull(q);
        assertEquals("insufficient_reserves",b.confirmHardExchange(p,q).code());eq("1000",b.balanceExact(p,"diamond"));
    }
    @Test void bothReserveZeroDoNotKillMetalReferenceButDoNotBackNotes() {
        BankBooks b=empty();UUID p=UUID.randomUUID();
        b.deposit(p,"copper",BigInteger.valueOf(500));b.deposit(p,"diamond",BigInteger.TEN);
        b.creditUnbacked(p,"note",BigInteger.TEN);
        eq("50",b.unitsPer("copper","diamond"));assertNull(b.unitsPer("diamond","note"));
    }
    @Test void custodyTreasuryReserveTransfersAreQuoteNeutralAndLockedIsNotDoubleCounted() {
        BankBooks b=empty();UUID p=UUID.randomUUID();
        b.deposit(p,"copper",BigInteger.valueOf(1000)); b.deposit(p,"diamond",BigInteger.TEN);
        for(String c:new String[]{"copper","diamond"}){
            BigDecimal amount="copper".equals(c)?d("1000"):d("10");
            assertTrue(b.transferTreasury(p,c,amount,true).success());eq("100",b.unitsPer("copper","diamond"));
            assertTrue(b.transferReserve(c,amount,true).success());eq("100",b.unitsPer("copper","diamond"));
            b.supply(c).setLocked(BigInteger.ONE);eq("100",b.unitsPer("copper","diamond"));
        }
        eq("1000",b.commodityHoldingsExact("copper"));eq("10",b.commodityHoldingsExact("diamond"));
        BankBooks loaded=BankBooks.fromJson(b.toJson(),BankConfig.defaults());eq("100",loaded.unitsPer("copper","diamond"));
    }
    @Test void totalZeroHasNoInventedQuoteAndAlphaCannotChangeTotalRatio() {
        BankBooks b=empty(); UUID p=UUID.randomUUID();assertNull(b.unitsPer("copper","diamond"));
        b.deposit(p,"diamond",BigInteger.TEN);assertNull(b.unitsPer("copper","diamond"));
        b.deposit(p,"copper",BigInteger.valueOf(500));eq("50",b.unitsPer("copper","diamond"));
        b.setAlpha("copper",d("4000"));b.setAlpha("diamond",d("0"));eq("50",b.unitsPer("copper","diamond"));
    }
    @Test void externalDepositsMoveReferenceAndInvalidateOldExchangeQuote() {
        BankBooks b=BankBooks.create(BankConfig.defaults());UUID p=UUID.randomUUID();
        b.creditUnbacked(p,"note",BigInteger.valueOf(2000));b.deposit(p,"diamond",BigInteger.ONE);
        var quote=b.quoteHardExchange("diamond","note",BigInteger.ONE);assertNotNull(quote);
        BigDecimal rate=b.unitsPer("diamond","note");
        b.deposit(p,"copper",BigInteger.valueOf(10000000));
        assertNotEquals(rate,b.unitsPer("diamond","note"));String before=b.toJson();
        assertEquals("quote_changed",b.confirmHardExchange(p,quote).code());assertEquals(before,b.toJson());
    }

    @Test void legacyPhysicalFeesMigrateOnceAndInterestBudgetIsNotAnAsset() {
        BankBooks seed=empty();UUID p=UUID.randomUUID();
        seed.deposit(p,"copper",BigInteger.valueOf(900));seed.deposit(p,"diamond",BigInteger.TEN);
        var old=com.google.gson.JsonParser.parseString(seed.toJson()).getAsJsonObject();
        old.remove("feeTreasuryVersion");
        old.getAsJsonObject("interestPool").addProperty("copper","100");
        BankBooks migrated=BankBooks.fromJson(old.toString(),BankConfig.defaults());
        eq("100",migrated.treasuryExact("copper"));eq("100",migrated.interestPoolExact("copper"));
        eq("900",migrated.custodyExact("copper"));eq("1000",migrated.commodityHoldingsExact("copper"));
        eq("100",migrated.unitsPer("copper","diamond"));
        String saved=migrated.toJson();BankBooks loaded=BankBooks.fromJson(saved,BankConfig.defaults());
        assertEquals(saved,loaded.toJson());eq("1000",loaded.commodityHoldingsExact("copper"));
        eq("100",loaded.unitsPer("copper","diamond"));
    }
    @Test void targetOneDiamondPerNoteDoesNotSetMetalCrossRateToOne() {
        BankBooks b=empty();UUID p=UUID.randomUUID();
        b.supply("diamond").setReserve(BigInteger.valueOf(500));
        b.deposit(p,"diamond",BigInteger.valueOf(500));
        b.deposit(p,"copper",BigInteger.valueOf(100000));
        b.creditUnbacked(p,"note",BigInteger.valueOf(1000));
        assertTrue(b.transferTreasury(p,"diamond",d("500"),true).success());
        eq("100",b.unitsPer("copper","diamond"));
        var preview=CentralAdjustment.preview(b,"reserve","diamond","1");
        assertTrue(preview.allowed(),preview.reason());assertTrue(preview.confirm(b).success());
        eq("1",b.unitsPer("diamond","note"));eq("100",b.unitsPer("copper","diamond"));
        eq("0",b.supply("copper").reserveExact());eq("100000",b.custodyExact("copper"));
    }
    @Test void allPhysicalHoldingsInTreasuryStillHaveIndependentCrossQuote() {
        BankBooks b=empty();UUID p=UUID.randomUUID();
        b.deposit(p,"diamond",BigInteger.TEN);b.deposit(p,"copper",BigInteger.valueOf(500));
        assertTrue(b.transferTreasury(p,"diamond",d("10"),true).success());
        assertTrue(b.transferTreasury(p,"copper",d("500"),true).success());
        eq("50",b.unitsPer("copper","diamond"));eq("0",b.custodyExact("diamond"));
        eq("0",b.supply("diamond").reserveExact());eq("0",b.supply("copper").reserveExact());
        assertNull(b.unitsPer("diamond","note"));
    }
}
