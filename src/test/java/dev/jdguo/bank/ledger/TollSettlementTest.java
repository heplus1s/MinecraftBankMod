package dev.jdguo.bank.ledger;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.NoteRegime;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TollSettlementTest {
 private static void eq(BigDecimal expected, BigDecimal actual){ assertEquals(0,expected.compareTo(actual)); }
 private static BankBooks books(){return BankBooks.create(BankConfig.defaults());}
 private static BigDecimal d(String s){return new BigDecimal(s);}
 private static void ok(TxResult r){assertTrue(r.success(),r.code()+":"+r.message());}
 private static void ok(dev.jdguo.bank.market.NoteTrade r){assertTrue(r.success(),r.code());}
 @Test void privateExactNotePersistsReplayAndConflictingRef(){
  var b=books();var a=UUID.randomUUID();var c=UUID.randomUUID();b.creditUnbacked(a,"note",BigInteger.TEN);ok(b.rewardTreasury(List.of(a),"note",d("3")));
  ok(b.tollPayment(a,c,"note",d("0.1234"),"e",false));eq(d("0.1234"),b.balanceExact(c,"note"));
  b=BankBooks.fromJson(b.toJson(),BankConfig.defaults());String saved=b.toJson();ok(b.tollPayment(a,c,"note",d("0.123400"),"e",false));assertEquals(saved,b.toJson());
  assertEquals("reference_conflict",b.tollPayment(a,c,"note",d("0.1235"),"e",false).code());
  assertEquals("reference_conflict",b.tollPayment(a,null,"note",d("0.1234"),"e",true).code());assertEquals(saved,b.toJson());ok(b.tollReceipt("e"));
 }
 @Test void commodityPrivateWholeUnitsOnly(){
  var b=books();var a=UUID.randomUUID();var c=UUID.randomUUID();b.deposit(a,"diamond",BigInteger.TEN);
  String before=b.toJson();assertFalse(b.tollPayment(a,c,"diamond",d("0.5"),"x",false).success());assertEquals(before,b.toJson());
  ok(b.tollPayment(a,c,"diamond",d("2"),"x",false));eq(d("8"),b.balanceExact(a,"diamond"));eq(d("2"),b.balanceExact(c,"diamond"));
 }
 @Test void directSystemNotesIndependentNoTreasuryUse(){
  var b=books();var a=UUID.randomUUID();b.creditUnbacked(a,"note",BigInteger.TEN);ok(b.rewardTreasury(List.of(a),"note",d("3")));
  var treasury=b.treasuryExact("note");var liability=b.supply("note").liabilityExact();ok(b.tollPayment(a,null,"note",d("0.1234"),"x",true));
  eq(d("0.1234"),b.systemSettlementBalance("toll","note"));eq(BigDecimal.ZERO,b.systemSettlementBalance("land","note"));eq(treasury,b.treasuryExact("note"));eq(liability,b.supply("note").liabilityExact());
 }
 @Test void commoditySystemBoughtByRealTreasuryFunds(){
  var b=books();var a=UUID.randomUUID();b.deposit(a,"diamond",BigInteger.TEN);b.creditUnbacked(a,"note",BigInteger.valueOf(1000));
  var treasury=b.treasuryExact("note");var custody=b.custodyExact("diamond");
  ok(b.tollPayment(a,null,"diamond",d("2"),"x",true));var proceeds=b.systemSettlementBalance("toll","note");assertTrue(proceeds.signum()>0);
  eq(treasury.subtract(proceeds),b.treasuryExact("note"));eq(d("2"),b.treasuryExact("diamond"));eq(custody.subtract(d("2")),b.custodyExact("diamond"));eq(d("8"),b.balanceExact(a,"diamond"));
 }
 @Test void failedSystemSettlementNoMutationAndNoReceipt(){
  var b=books();var a=UUID.randomUUID();b.deposit(a,"diamond",BigInteger.TEN);String before=b.toJson();
  assertFalse(b.tollPayment(a,null,"diamond",BigDecimal.ONE,"x",true).success());assertEquals(before,b.toJson());assertFalse(b.tollReceipt("x").success());
  assertEquals("insufficient_funds",b.tollPayment(a,null,"diamond",d("11"),"y",true).code());assertEquals(before,b.toJson());
 }
 private BankBooks personal(UUID issuer,UUID holder,String anchor){
  var b=books();b.creditUnbacked(issuer,"note",BigInteger.valueOf(1000));
  ok(b.createPersonalNote(issuer,"收费币",NoteRegime.FLOAT,"diamond",BigInteger.ONE));ok(b.issuePersonalNoteUnbacked(issuer,BigInteger.TEN));
  if(anchor.equals("note")){ok(b.switchPersonalReserve(issuer,"note"));}
  if(anchor.equals("note"))ok(b.rewardTreasury(List.of(issuer),"note",BigDecimal.TEN));else b.deposit(issuer,anchor,BigInteger.TEN);
  ok(b.depositPersonalReserveExact(issuer,BigDecimal.TEN));ok(b.setPersonalAutoListing(issuer,"收费币",false));
  ok(b.transferPersonalExact(issuer,holder,"personal:"+b.personalNote(issuer).id(),d("5")));return b;
 }
 @Test void personalPrivateDoesNotTouchBacking(){
  var a=UUID.randomUUID();var h=UUID.randomUUID();var c=UUID.randomUUID();var b=personal(a,h,"diamond");var id=b.personalNote(a).id();var reserve=b.effectiveBackingExact(b.personalNote(a));
  ok(b.tollPayment(h,c,"personal:"+id,d("0.1234"),"x",false));eq(d("0.1234"),b.personalHoldingExact(id,c));eq(reserve,b.effectiveBackingExact(b.personalNote(a)));
 }
 @Test void personalSystemClearsAndBuysTerminalThenReplays(){
  var a=UUID.randomUUID();var h=UUID.randomUUID();var b=personal(a,h,"diamond");var id=b.personalNote(a).id();var liability=b.supply("note").liabilityExact();
  ok(b.tollPayment(h,null,"personal:"+id,d("1"),"x",true));eq(d("4"),b.personalHoldingExact(id,h));eq(d("9"),b.effectiveBackingExact(b.personalNote(a)));eq(d("1"),b.personalRecovered(id));eq(d("1"),b.treasuryExact("diamond"));eq(liability,b.supply("note").liabilityExact());
  assertTrue(b.systemSettlementBalance("toll","note").signum()>0);b=BankBooks.fromJson(b.toJson(),BankConfig.defaults());String before=b.toJson();ok(b.tollPayment(h,null,"personal:"+id,d("1"),"x",true));assertEquals(before,b.toJson());
 }
 @Test void personalNoteBackedSystemNeedsNoTreasuryPurchase(){
  var a=UUID.randomUUID();var h=UUID.randomUUID();var b=personal(a,h,"note");var id=b.personalNote(a).id();var treasury=b.treasuryExact("note");
  ok(b.tollPayment(h,null,"personal:"+id,d("0.1234"),"x",true));eq(d("0.1234"),b.systemSettlementBalance("toll","note"));eq(treasury,b.treasuryExact("note"));
 }
 @Test void invalidAmountUnknownAndReferencesDoNotMutate(){
  var b=books();var a=UUID.randomUUID();var c=UUID.randomUUID();String before=b.toJson();
  for(var n:List.of("0","-1","0.12345"))assertFalse(b.tollPayment(a,c,"note",d(n),"x",false).success());
  assertFalse(b.tollPayment(a,c,"oops",BigDecimal.ONE,"x",false).success());assertFalse(b.tollPayment(a,c,"note",BigDecimal.ONE," ",false).success());assertEquals(before,b.toJson());
 }
 @Test void nestedCollateralClearsEveryLegAndPersists() {
  var issuer=UUID.randomUUID();var branch=UUID.randomUUID();var holder=UUID.randomUUID();var b=personal(issuer,branch,"diamond");var leaf=b.personalNote(issuer).id();
  ok(b.createPersonalNote(branch,"嵌套币",NoteRegime.FLOAT,"diamond",BigInteger.ONE));ok(b.issuePersonalNoteUnbacked(branch,BigInteger.valueOf(5)));
  ok(b.switchPersonalReserve(branch,"personal:"+leaf));ok(b.depositPersonalReserveExact(branch,d("5")));ok(b.setPersonalAutoListing(branch,"嵌套币",false));
  var top=b.personalNote(branch).id();ok(b.transferPersonalExact(branch,holder,"personal:"+top,d("3")));
  ok(b.tollPayment(holder,null,"personal:"+top,d("1"),"nested",true));
  eq(d("4"),b.effectiveBackingExact(b.personalNote(branch)));eq(d("9"),b.effectiveBackingExact(b.personalNote(issuer)));
  eq(d("1"),b.personalRecovered(top));eq(d("1"),b.personalRecovered(leaf));eq(d("1"),b.treasuryExact("diamond"));
  b.checkInvariants();b=BankBooks.fromJson(b.toJson(),BankConfig.defaults());b.checkInvariants();ok(b.tollReceipt("nested"));
 }
 @Test void personalTreasuryShortfallNeverDebitsOrRecovers() {
  var issuer=UUID.randomUUID();var holder=UUID.randomUUID();var b=personal(issuer,holder,"diamond");var id=b.personalNote(issuer).id();
  ok(b.rewardTreasury(List.of(UUID.randomUUID()),"note",b.treasuryExact("note")));
  String before=b.toJson();assertEquals("insufficient_treasury",b.tollPayment(holder,null,"personal:"+id,d("1"),"short",true).code());assertEquals(before,b.toJson());assertFalse(b.tollReceipt("short").success());
 }
 @Test void precisionRefusalLeavesNoReceiptAndCloneUnpublished() {
  var issuer=UUID.randomUUID();var holder=UUID.randomUUID();var original=personal(issuer,holder,"diamond");var id=original.personalNote(issuer).id();String before=original.toJson();
  var candidate=original.copyExact();var r=candidate.tollPayment(holder,null,"personal:"+id,d("0.1234"),"fraction",true);
  assertEquals("below_precision",r.code());assertEquals(before,original.toJson());assertFalse(candidate.tollReceipt("fraction").success());
 }
 @Test void previewAndExtremeAmountsAreSafe() {
  var issuer=UUID.randomUUID();var holder=UUID.randomUUID();var b=personal(issuer,holder,"diamond");String before=b.toJson();var token="personal:"+b.personalNote(issuer).id();
  ok(b.copyExact().previewTollSystem(token,d("1")));assertEquals("below_precision",b.copyExact().previewTollSystem(token,d("0.1234")).code());assertEquals(before,b.toJson());
  for(var amount:List.of(new BigDecimal("1E+100000000"),new BigDecimal("1E-100000000"))) {
   assertEquals("bad_amount",b.tollPayment(holder,null,token,amount,"large",true).code());assertEquals("bad_amount",b.previewTollSystem(token,amount).code());
  }
  assertEquals(before,b.toJson());
 }
 @Test void copperSettlementAndLegacyRollbackCannotTouchToll() {
  var b=books();var a=UUID.randomUUID();b.deposit(a,"copper",BigInteger.valueOf(100));b.deposit(a,"diamond",BigInteger.TEN);b.creditUnbacked(a,"note",BigInteger.valueOf(1000));
  ok(b.tollPayment(a,null,"copper",d("2"),"copper",true));eq(d("2"),b.treasuryExact("copper"));assertTrue(b.systemSettlementBalance("toll","note").signum()>0);
  String before=b.toJson();assertFalse(b.rollbackShopPayment("toll:copper").success());assertFalse(b.reversePersonalSettlement("toll:copper").success());assertEquals(before,b.toJson());b.checkInvariants();
 }
 @Test void authoritativeStateCasAndPaymentShareCheckpoint() {
  var b=books();var a=UUID.randomUUID();var to=UUID.randomUUID();b.deposit(a,"diamond",BigInteger.TEN);
  String payload="{\"blocked\":[\"region/player\"],\"orders\":{\"ref\":{\"status\":\"PENDING\"}}}";
  ok(b.saveTollState(0,payload));assertEquals(1,b.tollState().get("revision").getAsLong());
  String before=b.toJson();assertEquals("state_conflict",b.saveTollState(0,"{}").code());assertEquals(before,b.toJson());
  ok(b.tollPayment(a,to,"diamond",BigDecimal.ONE,"state-ref",false));
  b=BankBooks.fromJson(b.toJson(),BankConfig.defaults());assertEquals(payload,b.tollState().get("payload").getAsString());ok(b.tollReceipt("state-ref"));
  ok(b.tollPayment(a,to,"diamond",BigDecimal.ONE,"state-ref",false));eq(BigDecimal.ONE,b.balanceExact(to,"diamond"));
  ok(b.saveTollState(1,"{\"paid\":true}"));assertEquals(2,b.tollState().get("revision").getAsLong());
 }
 @Test void badStateCannotReplaceAuthoritativePayload(){
  var b=books();ok(b.saveTollState(0,"{}"));String before=b.toJson();
  for(String json:List.of("[]","not-json","{bad", " ".repeat(16*1024*1024+1)))assertEquals("bad_state",b.saveTollState(1,json).code());
  assertEquals(before,b.toJson());assertEquals("state_conflict",b.saveTollState(-1,"{}").code());
 }
 @Test void downgradeCannotMasqueradeAsFirstMigration() {
  var b=books();var a=UUID.randomUUID();var to=UUID.randomUUID();b.deposit(a,"diamond",BigInteger.TEN);ok(b.saveTollState(0,"{}"));ok(b.tollPayment(a,to,"diamond",BigDecimal.ONE,"downgrade",false));
  assertFalse(b.tollStateMissingAfterPayments());var json=com.google.gson.JsonParser.parseString(b.toJson()).getAsJsonObject();json.remove("tollState");
  var downgraded=BankBooks.fromJson(json.toString(),BankConfig.defaults());assertTrue(downgraded.tollStateMissingAfterPayments());
 }
 @Test void migrationMarkerSurvivesLegacyOnlyDowngradedWriter() {
  var b=books();ok(b.saveTollState(0,"{\"legacyPending\":true}"));
  ok(b.postMarketMovement("toll:legacy-reference","transfer|legacy",BigInteger.ONE,()->TxResult.ok(BigInteger.ONE,"")));
  var json=com.google.gson.JsonParser.parseString(b.toJson()).getAsJsonObject();json.remove("tollState");
  var legacySaved=BankBooks.fromJson(json.toString(),BankConfig.defaults());assertTrue(legacySaved.tollStateMissingAfterPayments());
  assertEquals("state_missing",legacySaved.saveTollState(0,"{}").code());
 }
}
