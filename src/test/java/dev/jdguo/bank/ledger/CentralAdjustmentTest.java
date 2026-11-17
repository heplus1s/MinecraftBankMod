package dev.jdguo.bank.ledger;
import dev.jdguo.bank.currency.BankConfig;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class CentralAdjustmentTest {
    private BankBooks fixture() {
        BankBooks b=BankBooks.create(BankConfig.defaults());
        for(String c:new String[]{"note","copper","diamond"}) b.setAlpha(c,BigDecimal.ZERO);
        b.supply("copper").setReserve(BigInteger.ZERO); b.supply("diamond").setReserve(BigInteger.valueOf(1000));
        b.supply("note").setLiability(BigInteger.valueOf(100)); return b;
    }
    private static void eq(String expected,BigDecimal value){assertEquals(0,new BigDecimal(expected).compareTo(value));}
    @Test void quantityIsSeparateFromReserveAndRejectsReplay(){
        BankBooks b=fixture();String before=b.toJson();var p=CentralAdjustment.preview(b,"quantity","increase","100");
        assertTrue(p.allowed(),p.reason());assertEquals(before,b.toJson());assertTrue(p.confirm(b).success());
        eq("200",b.supply("note").liabilityExact());eq("1000",b.supply("diamond").reserveExact());eq("5",b.unitsPer("diamond","note"));
        assertFalse(p.confirm(b).success());assertTrue(CentralAdjustment.preview(b,"quantity","decrease","100").confirm(b).success());eq("100",b.supply("note").liabilityExact());
    }
    @Test void reserveTargetMovesRealAssetsWithoutChangingIssue(){
        BankBooks b=fixture();b.transferReserve("diamond",new BigDecimal("500"),false);
        String before=b.toJson();var p=CentralAdjustment.preview(b,"reserve","diamond","8");
        assertTrue(p.allowed(),p.reason());assertEquals(before,b.toJson());assertEquals(BigInteger.valueOf(300),p.amount());
        assertTrue(p.confirm(b).success());eq("800",b.supply("diamond").reserveExact());eq("200",b.treasuryExact("diamond"));eq("100",b.supply("note").liabilityExact());eq("8",b.unitsPer("diamond","note"));
    }
    @Test void downwardReserveTargetPreservesCustodyAndLockedAssets(){
        BankBooks b=fixture();b.deposit(UUID.randomUUID(),"diamond",BigInteger.valueOf(600));b.supply("diamond").setLocked(BigInteger.valueOf(700));
        var p=CentralAdjustment.preview(b,"reserve","diamond","8");assertTrue(p.allowed(),p.reason());assertTrue(p.confirm(b).success());
        eq("600",b.custodyExact("diamond"));eq("700",b.supply("diamond").lockedExact());eq("200",b.treasuryExact("diamond"));
        assertFalse(CentralAdjustment.preview(b,"reserve","diamond","6").allowed());
    }
    @Test void noTreasuryCannotUseCustodyForIncrease(){
        BankBooks b=fixture();b.deposit(UUID.randomUUID(),"diamond",BigInteger.valueOf(99999));String before=b.toJson();
        assertFalse(CentralAdjustment.preview(b,"reserve","diamond","11").allowed());assertEquals(before,b.toJson());
    }
    @Test void stalePreviewRefusedAndPlayerNotesCannotBeRetired(){
        BankBooks b=fixture();var p=CentralAdjustment.preview(b,"quantity","increase","1");b.deposit(UUID.randomUUID(),"diamond",BigInteger.ONE);
        assertFalse(p.confirm(b).success());b.creditHeld(UUID.randomUUID(),"note",BigInteger.valueOf(200));
        assertFalse(CentralAdjustment.preview(b,"quantity","decrease","101").allowed());
    }
    @Test void targetInputPrecisionAndQuantityValidation(){
        BankBooks b=fixture();for(String n:new String[]{"0","-1","1.00001","NaN"})assertFalse(CentralAdjustment.preview(b,"reserve","diamond",n).allowed());
        for(String n:new String[]{"0","-1","1.2","oops"})assertFalse(CentralAdjustment.preview(b,"quantity","increase",n).allowed());
    }
    @Test void nonlinearMetalFeedbackUsesActualQuote(){
        BankBooks b=fixture();b.supply("copper").setReserve(BigInteger.valueOf(10000));b.setAlpha("copper",new BigDecimal("700"));b.setAlpha("diamond",new BigDecimal("350"));b.setAlpha("note",new BigDecimal("600"));
        b.transferReserve("diamond",new BigDecimal("300"),false);
        BankBooks expected=BankBooks.fromJson(b.toJson(),BankConfig.defaults());expected.transferReserve("diamond",new BigDecimal("100"),true);
        BigDecimal target=expected.unitsPer("diamond","note");var p=CentralAdjustment.preview(b,"reserve","diamond",target.toPlainString());
        assertTrue(p.allowed(),p.reason());assertTrue(p.confirm(b).success());assertEquals(target,b.unitsPer("diamond","note"));eq("100",b.supply("note").liabilityExact());
    }
    @Test void copperTargetAlsoQuotesRealFeedbackAndConservesTotal() {
        BankBooks b=fixture();b.supply("copper").setReserve(BigInteger.valueOf(10000));
        b.setAlpha("copper",new BigDecimal("700"));b.setAlpha("diamond",new BigDecimal("350"));b.setAlpha("note",new BigDecimal("600"));
        b.transferReserve("copper",new BigDecimal("3000"),false);
        BankBooks expected=BankBooks.fromJson(b.toJson(),BankConfig.defaults());expected.transferReserve("copper",new BigDecimal("1000"),true);
        BigDecimal target=expected.unitsPer("diamond","note");var p=CentralAdjustment.preview(b,"reserve","copper",target.toPlainString());
        assertTrue(p.allowed(),p.reason());assertTrue(p.confirm(b).success());
        eq("10000",b.supply("copper").reserveExact().add(b.treasuryExact("copper")));
        assertTrue(b.unitsPer("diamond","note").subtract(target).abs().compareTo(new BigDecimal("0.001"))<=0);
    }
    @Test void integerRoundingReportsActualRatherThanClaimingTarget() {
        BankBooks b=fixture();var p=CentralAdjustment.preview(b,"reserve","diamond","8.1234");
        assertTrue(p.allowed(),p.reason());assertTrue(p.confirm(b).success());
        assertEquals(p.rateAfter(),b.unitsPer("diamond","note"));assertNotEquals(p.target(),p.rateAfter());
        assertEquals(0,b.supply("diamond").reserveExact().remainder(BigDecimal.ONE).signum());
    }
    @Test void scientificAndExcessiveTargetNotationRejectedBeforeArithmetic() {
        BankBooks b=fixture();String before=b.toJson();
        for(String n:new String[]{"1E+2147483647","1E-2147483647","1e3","100000000000000000000000000","+1",".5"})
            assertFalse(CentralAdjustment.preview(b,"reserve","diamond",n).allowed(),n);
        assertEquals(before,b.toJson());
    }
    @Test void exactCopyDoesNotInventRemovedCurrencyOrOpeningAssets() {
        BankConfig d=BankConfig.defaults();
        BankConfig c=new BankConfig("diamond",d.feeFraction(),d.interestFraction(),d.personalInterestFraction(),
            d.floatCentralReserve(),d.pegCentralReserve(),d.hardCentralReserve(),d.clampMin(),d.clampMax(),d.outletRadius(),
            d.sidebar(),d.bossbar(),d.tab(),d.hologram(),d.joinMessage(),
            d.currencies().stream().filter(x->!x.id().equals("copper")).toList());
        BankBooks b=BankBooks.create(c);b.setAlpha("note",BigDecimal.ZERO);b.supply("note").setLiability(BigInteger.valueOf(100));
        assertNull(b.copyExact().currency("copper"));assertEquals(b.toJson(),b.copyExact().toJson());
        String before=b.toJson();var p=CentralAdjustment.preview(b,"reserve","diamond","8");
        assertTrue(p.allowed(),p.reason());assertEquals(before,b.toJson());assertEquals(BigInteger.valueOf(200),p.amount());
        assertTrue(p.confirm(b).success());eq("8",b.unitsPer("diamond","note"));assertNull(b.currency("copper"));
        assertFalse(CentralAdjustment.preview(b,"reserve","copper","8").allowed());
    }
    @Test void customCurrencyAndPoliciesArePreservedInPreviewCopies() {
        BankConfig d=BankConfig.defaults();
        var note=new dev.jdguo.bank.currency.CurrencyDef("custom",dev.jdguo.bank.currency.CurrencyKind.VIRTUAL,
            "minecraft:paper","测试券","Test note","yellow",BigInteger.valueOf(1000),BigInteger.valueOf(100),
            new BigDecimal("250"),true,"diamond",BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE);
        BankConfig c=new BankConfig("diamond",new BigDecimal("0.03"),new BigDecimal("0.01"),new BigDecimal("0.005"),
            new BigDecimal("0.1"),new BigDecimal("0.2"),new BigDecimal("0.3"),new BigDecimal("0.001"),new BigDecimal("10000"),4,
            false,true,false,true,false,java.util.List.of(d.currency("diamond"),note));
        BankBooks b=BankBooks.create(c);b.setAlpha("diamond",new BigDecimal("123"));b.supply("custom").setLiability(BigInteger.valueOf(100));
        BankBooks copy=b.copyExact();assertEquals(b.toJson(),copy.toJson());assertNull(copy.currency("note"));assertNull(copy.currency("copper"));
        var p=CentralAdjustment.preview(b,"quantity","increase","50");assertTrue(p.allowed(),p.reason());assertEquals("custom",p.currency());
        assertTrue(p.confirm(b).success());eq("150",b.supply("custom").liabilityExact());assertNull(b.currency("note"));
    }
}
