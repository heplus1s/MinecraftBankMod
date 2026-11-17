package dev.jdguo.bank.ledger;
import dev.jdguo.bank.currency.BankConfig;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TreasuryRewardTest {
 private static void eq(String n, BigDecimal v){assertEquals(0,new BigDecimal(n).compareTo(v));}
 @Test void multiPlayerCommodityConservesAssetsAndDeduplicates(){
  var b=BankBooks.create(BankConfig.defaults());var admin=UUID.randomUUID();var a=UUID.randomUUID();var c=UUID.randomUUID();
  b.deposit(admin,"copper",BigInteger.valueOf(100));b.transferTreasury(admin,"copper",new BigDecimal("100"),true);
  var reserve=b.supply("copper").reserveExact();assertTrue(b.rewardTreasury(List.of(a,c,a),"copper",new BigDecimal("25")).success());
  eq("25",b.balanceExact(a,"copper"));eq("25",b.balanceExact(c,"copper"));eq("50",b.treasuryExact("copper"));eq("50",b.custodyExact("copper"));assertEquals(reserve,b.supply("copper").reserveExact());
  var loaded=BankBooks.fromJson(b.toJson(),BankConfig.defaults());eq("25",loaded.balanceExact(a,"copper"));eq("50",loaded.treasuryExact("copper"));
 }
 @Test void invalidAndInsufficientAreWholeBatchNoOps(){
  var b=BankBooks.create(BankConfig.defaults());var a=UUID.randomUUID();String before=b.toJson();
  assertFalse(b.rewardTreasury(List.of(a),"copper",new BigDecimal("1.5")).success());
  assertFalse(b.rewardTreasury(List.of(a),"copper",new BigDecimal("100")).success());
  assertFalse(b.rewardTreasury(List.of(),"copper",BigDecimal.ONE).success());
  assertFalse(b.rewardTreasury(List.of(a),"personal:unknown",BigDecimal.ONE).success());assertEquals(before,b.toJson());
 }
 @Test void noteRewardDoesNotMintAndAllowsFourDecimals(){
  var b=BankBooks.create(BankConfig.defaults());var a=UUID.randomUUID();var c=UUID.randomUUID();
  b.creditUnbacked(UUID.randomUUID(),"note",BigInteger.TEN);var liability=b.supply("note").liabilityExact();
  assertTrue(b.rewardTreasury(List.of(a,c),"note",new BigDecimal("0.1234")).success());
  eq("0.1234",b.balanceExact(a,"note"));assertEquals(liability,b.supply("note").liabilityExact());eq("9.7532",b.treasuryExact("note"));
 }
 private BankBooks personalFixture(UUID issuer) {
  var b=BankBooks.create(BankConfig.defaults());
  b.creditUnbacked(UUID.randomUUID(),"note",BigInteger.valueOf(1000));
  assertTrue(b.createPersonalNote(issuer,"奖励币",dev.jdguo.bank.market.NoteRegime.FLOAT,"diamond",BigInteger.ONE).success());
  assertTrue(b.issuePersonalNoteUnbacked(issuer,BigInteger.TEN).success());
  b.deposit(issuer,"diamond",BigInteger.TEN);assertTrue(b.depositPersonalReserveExact(issuer,BigDecimal.TEN).success());
  return b;
 }
 @Test void personalRewardBuysRealInventoryWithNoNewIssueAndReloads(){
  UUID issuer=UUID.randomUUID(),a=UUID.randomUUID(),c=UUID.randomUUID();var b=personalFixture(issuer);var coin=b.personalNote(issuer).id();
  BigDecimal held=b.personalHoldingExact(coin,issuer),treasury=b.treasuryExact("note"),liability=b.supply("note").liabilityExact();
  BigDecimal reserve=b.effectiveBackingExact(b.personalNote(issuer));
  var result=b.rewardPersonalFromTreasury(List.of(a,c),coin,new BigDecimal("0.1234"));assertTrue(result.success(),result.message());
  eq("0.1234",b.personalHoldingExact(coin,a));eq("0.1234",b.personalHoldingExact(coin,c));
  eq("0.2468",held.subtract(b.personalHoldingExact(coin,issuer)));
  assertEquals(0,treasury.subtract(b.treasuryExact("note")).compareTo(b.balanceExact(issuer,"note")));
  assertEquals(liability,b.supply("note").liabilityExact());assertEquals(reserve,b.effectiveBackingExact(b.personalNote(issuer)));
  var loaded=BankBooks.fromJson(b.toJson(),BankConfig.defaults());eq("0.1234",loaded.personalHoldingExact(coin,c));
 }
 @Test void personalInsufficientOrPausedDoesNotChangeAnyAccount(){
  UUID issuer=UUID.randomUUID(),a=UUID.randomUUID();var b=personalFixture(issuer);var coin=b.personalNote(issuer).id();
  String before=b.toJson();assertFalse(b.rewardPersonalFromTreasury(List.of(a),coin,new BigDecimal("99999")).success());assertEquals(before,b.toJson());
  b.setPersonalAutoListing(issuer,"奖励币",false);before=b.toJson();assertFalse(b.rewardPersonalFromTreasury(List.of(a),coin,BigDecimal.ONE).success());assertEquals(before,b.toJson());
 }
 @Test void issuerAsRecipientRetainsRewardButOnlyGetsRealTreasuryPayment(){
  UUID issuer=UUID.randomUUID(),a=UUID.randomUUID();var b=personalFixture(issuer);var coin=b.personalNote(issuer).id();var held=b.personalHoldingExact(coin,issuer);
  assertTrue(b.rewardPersonalFromTreasury(List.of(issuer,a),coin,new BigDecimal("0.5")).success());
  eq("0.5",held.subtract(b.personalHoldingExact(coin,issuer)));eq("0.5",b.personalHoldingExact(coin,a));
 }
 @Test void transactionCloneUsesLiveDefinitionsAndRewardDoesNotInjectDefaults() throws Exception {
  String runtime=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/jdguo/bank/persistence/BankRuntime.java"));
  assertTrue(runtime.contains("BankBooks candidate = books().copyExact();"));
  assertFalse(runtime.contains("BankBooks candidate = BankBooks.fromJson(books().toJson(), config)"));
  var d=BankConfig.defaults();
  var custom=new BankConfig(d.numeraire(),d.feeFraction(),d.interestFraction(),d.personalInterestFraction(),
    d.floatCentralReserve(),d.pegCentralReserve(),d.hardCentralReserve(),d.clampMin(),d.clampMax(),d.outletRadius(),
    d.sidebar(),d.bossbar(),d.tab(),d.hologram(),d.joinMessage(),d.currencies().stream().filter(x->!x.id().equals("copper")).toList());
  var live=BankBooks.create(custom);UUID a=UUID.randomUUID();live.creditUnbacked(a,"note",BigInteger.TEN);
  String before=live.toJson();var candidate=live.copyExact();assertEquals(before,candidate.toJson());
  assertTrue(candidate.rewardTreasury(List.of(a),"note",BigDecimal.ONE).success());
  assertNull(candidate.currency("copper"));assertEquals(before,live.toJson());
  assertEquals(candidate.toJson(),candidate.copyExact().toJson());
 }
}
