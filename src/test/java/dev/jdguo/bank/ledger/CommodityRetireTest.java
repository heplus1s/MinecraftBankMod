package dev.jdguo.bank.ledger;
import dev.jdguo.bank.currency.BankConfig;
import java.math.*;import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CommodityRetireTest {
 @Test void rejectsMetalRetirementWithPersonalCollateralAndPreservesEveryField(){
  for(String currency:new String[]{"diamond","copper"}){
   var b=BankBooks.create(BankConfig.defaults());var p=UUID.randomUUID();
   assertTrue(b.deposit(p,currency,BigInteger.valueOf(100)).success());
   assertTrue(b.createPersonalNote(p,"测试币",currency.equals("copper"),BigInteger.ONE).success());
   assertTrue(b.depositPersonalReserve(p,BigInteger.valueOf(100)).success());
   String before=b.toJson();assertEquals("not_issuable",b.retire(p,currency,BigInteger.ONE).code());assertEquals(before,b.toJson());b.checkInvariants();
  }
 }
 @Test void rejectsMetalWithLegacyExcessLiabilityWithoutNormalizingData(){
  var b=BankBooks.create(BankConfig.defaults());var p=UUID.randomUUID();
  for(String c:new String[]{"diamond","copper"}){
   b.supply(c).addLiability(BigInteger.valueOf(100));String before=b.toJson();
   assertEquals("not_issuable",b.retire(p,c,BigInteger.ONE).code());assertEquals(before,b.toJson());
  }
 }
 @Test void centralRetirementStillBurnsOnlyTreasuryNotes(){
  var b=BankBooks.create(BankConfig.defaults());var p=UUID.randomUUID();
  assertTrue(b.creditUnbacked(p,"note",BigInteger.TEN).success());
  assertTrue(b.retire(p,"note",BigInteger.ONE).success());
  assertEquals(BigInteger.valueOf(9),b.supply("note").liability());
  assertEquals(0,new BigDecimal("9").compareTo(b.treasuryExact("note")));b.checkInvariants();
 }
 @Test void depositWithdrawalAndTreasuryTransfersKeepCustodyAndLiabilityTogether(){
  for(String c:new String[]{"diamond","copper"}){
   var b=BankBooks.create(BankConfig.defaults());var p=UUID.randomUUID();
   assertTrue(b.deposit(p,c,BigInteger.valueOf(100)).success());
   assertTrue(b.transferTreasury(p,c,BigDecimal.TEN,true).success());
   assertTrue(b.transferTreasury(p,c,BigDecimal.ONE,false).success());
   assertTrue(b.withdraw(p,c,BigInteger.ONE,false).success());
   assertEquals(b.supply(c).depositVaultExact(),b.supply(c).liabilityExact());
   assertEquals(b.balanceExact(p,c),b.supply(c).depositVaultExact());b.checkInvariants();
  }
 }
}
