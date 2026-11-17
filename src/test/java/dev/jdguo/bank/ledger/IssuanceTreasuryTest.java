package dev.jdguo.bank.ledger;

import com.google.gson.*;
import dev.jdguo.bank.currency.BankConfig;
import java.math.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class IssuanceTreasuryTest {
    final UUID admin=UUID.randomUUID(), player=UUID.randomUUID();
    static void eq(String n, BigDecimal v){assertEquals(0,new BigDecimal(n).compareTo(v));}
    BankBooks fresh(){return BankBooks.create(BankConfig.defaults());}
    @Test void issuedNotesCanFundShopWithoutAdministratorPersonalDeposit(){
        BankBooks b=fresh();var p=CentralAdjustment.preview(b,"quantity","increase","1000");
        assertTrue(p.allowed());eq("1000",p.treasuryAfter());assertTrue(p.confirm(b).success());
        eq("1000",b.treasuryExact("note"));eq("0",b.balanceExact(admin,"note"));
        assertTrue(b.fundSystemAccount(admin,true,"shop",new BigDecimal("300"),new BigDecimal("1000"),BigDecimal.ZERO).success());
        assertTrue(b.fundSystemAccount(admin,true,"land",new BigDecimal("200"),new BigDecimal("700"),BigDecimal.ZERO).success());
        eq("500",b.treasuryExact("note"));eq("300",b.systemFundingBalance("shop"));eq("200",b.systemFundingBalance("land"));eq("1000",b.supply("note").liabilityExact());b.checkInvariants();
        assertFalse(b.retire(admin,"note",BigInteger.valueOf(501)).success());assertTrue(b.retire(admin,"note",BigInteger.valueOf(500)).success());
        eq("0",b.treasuryExact("note"));eq("500",b.supply("note").liabilityExact());b.checkInvariants();
    }
    @Test void bothIssuanceOverloadsAndBackedIssueEnterTreasury(){
        BankBooks b=fresh();assertTrue(b.creditUnbacked(admin,"note",new BigDecimal("10.125" )).success());
        assertTrue(b.creditUnbacked(admin,"note",BigInteger.TEN).success());
        assertTrue(b.creditBacked(admin,"note",BigInteger.TEN).success());eq("30.125",b.treasuryExact("note"));eq("30.125",b.supply("note").liabilityExact());b.checkInvariants();
    }
    @Test void hardExchangeTransfersTreasuryWithoutNewIssuance(){
        BankBooks b=fresh();b.creditUnbacked(admin,"note",BigInteger.valueOf(100));b.deposit(player,"diamond",BigInteger.TEN);
        var q=b.quoteHardExchange("diamond","note",BigInteger.ONE);assertNotNull(q);assertTrue(b.confirmHardExchange(player,q).success());
        eq("100",b.treasuryExact("note").add(b.balanceExact(player,"note")));eq("100",b.supply("note").liabilityExact());b.checkInvariants();
    }
    @Test void legacyRemainderAllocationIsIdempotentAndPreservesAllClaims(){
        BankBooks b=fresh();b.creditUnbacked(admin,"note",BigInteger.valueOf(100));b.creditHeld(player,"note",BigInteger.valueOf(30));
        b.fundSystemAccount(admin,true,"shop",new BigDecimal("10"),new BigDecimal("100"),BigDecimal.ZERO);
        JsonObject j=JsonParser.parseString(b.toJson()).getAsJsonObject();j.remove("centralIssuanceTreasuryVersion");
        // The historical issuance was 100: 10 already assigned to shop, 20 already owned,
        // 70 still unallocated. Other accounts and fractional system assets also exist.
        j.getAsJsonObject("centralTreasury").addProperty("note","20");
        JsonObject asset=new JsonObject();asset.addProperty("note","0.125");
        j.getAsJsonObject("recursiveClearing").getAsJsonObject("assets").add("system:test",asset);
        BankBooks loaded=BankBooks.fromJson(j.toString(),BankConfig.defaults());
        eq("89.875",loaded.treasuryExact("note"));eq("130",loaded.supply("note").liabilityExact());eq("30",loaded.balanceExact(player,"note"));eq("10",loaded.systemFundingBalance("shop"));
        loaded.checkInvariants();String saved=loaded.toJson();assertEquals(saved,loaded.copyExact().toJson());
        assertEquals(saved,BankBooks.fromJson(saved,BankConfig.defaults()).toJson());
    }
    @Test void negativeLegacyGapNeverCreatesMoney(){
        BankBooks b=fresh();b.creditHeld(player,"note",BigInteger.TEN);JsonObject j=JsonParser.parseString(b.toJson()).getAsJsonObject();j.remove("centralIssuanceTreasuryVersion");
        j.getAsJsonObject("centralTreasury").addProperty("note","20");BankBooks loaded=BankBooks.fromJson(j.toString(),BankConfig.defaults());
        eq("20",loaded.treasuryExact("note"));eq("10",loaded.supply("note").liabilityExact());assertThrows(AssertionError.class,loaded::checkInvariants);
    }
    @Test void legacyPaperAndChecksAreNeverReallocated() {
        BankBooks b=fresh();b.creditUnbacked(admin,"note",BigInteger.valueOf(100));b.creditHeld(player,"note",BigInteger.valueOf(30));
        assertTrue(b.issueCentralPaper(player,BigInteger.TEN).result().success());
        assertTrue(b.issueCheck(player,"note",BigInteger.TEN,"player",admin,true).success());
        JsonObject j=JsonParser.parseString(b.toJson()).getAsJsonObject();j.remove("centralIssuanceTreasuryVersion");j.getAsJsonObject("centralTreasury").addProperty("note","0");
        BankBooks restored=BankBooks.fromJson(j.toString(),BankConfig.defaults());eq("100",restored.treasuryExact("note"));eq("130",restored.supply("note").liabilityExact());eq("10",restored.balanceExact(player,"note"));restored.checkInvariants();
    }
    @Test void versionedZeroTreasuryNeverRecreatesAssignedFunds() {
        BankBooks b=fresh();b.creditUnbacked(admin,"note",BigInteger.valueOf(100));
        assertTrue(b.fundSystemAccount(admin,true,"shop",new BigDecimal("100"),new BigDecimal("100"),BigDecimal.ZERO).success());
        BankBooks restored=BankBooks.fromJson(b.toJson(),BankConfig.defaults());eq("0",restored.treasuryExact("note"));eq("100",restored.systemFundingBalance("shop"));assertEquals(restored.toJson(),restored.copyExact().toJson());
    }
}
