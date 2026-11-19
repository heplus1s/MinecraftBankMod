package dev.jdguo.bank.ledger;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.*;
import java.math.*;
import java.util.*;

/** Executable real-ledger regression, without Minecraft or JUnit runtime. */
public final class RecursiveClearingRegression {
 static BigDecimal d(String s) { return new BigDecimal(s); }
 static void eq(BigDecimal a,String b) { if(a.compareTo(d(b))!=0) throw new AssertionError(a+" != "+b); }
 static void ok(boolean v,String m) { if(!v) throw new AssertionError(m); }
 static PersonalNote coin(BankBooks b,UUID owner,String name,String anchor,int n) {
  ok(b.createPersonalNote(owner,name,NoteRegime.FLOAT,"diamond",BigInteger.ONE).success(),"create");
  ok(b.issuePersonalNoteUnbacked(owner,BigInteger.valueOf(n)).success(),"issue");
  if (!"diamond".equals(anchor)) ok(b.switchPersonalReserve(owner,anchor).success(),"switch anchor");
  return b.personalNote(owner);
 }
 public static void main(String[] args) {
  UUID a=UUID.randomUUID(), z=UUID.randomUUID();
  BankBooks b=BankBooks.create(BankConfig.defaults());
  PersonalNote bottom=coin(b,z,"泉","diamond",100);
  b.deposit(z,"diamond",BigInteger.TEN);
  ok(b.depositPersonalReserveExact(z,d("10")).success(),"base reserve");
  PersonalNote top=coin(b,a,"云","personal:"+bottom.id(),100);
  ok(b.transferPersonal(z,a,"泉",BigInteger.valueOf(50)).success(),"transfer collateral");
  ok(b.depositPersonalReserveExact(a,d("50")).success(),"nested reserve");
  BigDecimal vault=b.supply("diamond").depositVaultExact();
  var q=b.quotePersonalClearing(top.id(),d("5"));
  ok(q.success() && q.legs().size()==2 && q.currency().equals("diamond"),"nested quote"); eq(q.amount(),"0.25");
  ok(b.settlePersonalToSystem(a,top.id(),d("5"),"shop","one").success(),"settle");
  eq(b.personalRecovered(top.id()),"5"); eq(b.personalRecovered(bottom.id()),"2.5");
  eq(b.effectiveBackingExact(top),"47.5"); eq(b.effectiveBackingExact(bottom),"9.75");
  eq(b.systemSettlementBalance("shop","diamond"),"0.25");
  eq(b.supply("diamond").depositVaultExact(),vault.toPlainString()); b.checkInvariants();
  ok(b.settlePersonalToSystem(a,top.id(),d("5.0"),"shop","one").success(),"replay");
  eq(b.systemSettlementBalance("shop","diamond"),"0.25");
  ok(!b.settlePersonalToSystem(a,top.id(),d("6"),"shop","one").success(),"conflict");
  b=BankBooks.fromJson(b.toJson(),BankConfig.defaults()); top=b.personalNote(a); bottom=b.personalNote(z); b.checkInvariants();
  eq(b.personalRecovered(bottom.id()),"2.5"); ok(b.personalSettlements(bottom.id(),5).size()==1,"persisted nested history");
  ok(b.noteHasPending(top.id()) && b.noteHasPending(bottom.id()),"pending delivery survives reload");
  eq(b.systemAvailableBalance("shop","diamond"),"0"); eq(b.personalRecoveredAvailable(top.id()),"0");
  ok(!b.switchPersonalReserve(a,"copper").success(),"pending blocks anchor change");
  ok(!b.returnRecoveredPersonal(a,d("1")).success(),"pending blocks recovery return");
  ok(b.completeSystemPayment("one").success(),"durable delivery proof");
  ok(b.completeSystemPayment("one").success(),"completion replay");
  eq(b.personalSystemCapacity(top.id(),"shop"),"5");
  var reverseQuote=b.quotePersonalFromSystem(top.id(),d("5")); ok(reverseQuote.success(),"reverse quote"); eq(reverseQuote.amount(),"0.25");
  ok(b.settlePersonalFromSystem(z,top.id(),d("5"),"shop","new-outbound").success(),"legacy reverse receipt compatibility fixture");
  eq(b.personalHoldingExact(top.id(),z),"5"); eq(b.personalRecovered(top.id()),"0"); eq(b.personalRecovered(bottom.id()),"0");
  eq(b.systemSettlementBalance("shop","diamond"),"0"); b.checkInvariants();
  ok(b.transferShopReferenced(z,"personal:"+top.id(),d("5.0"),false,"new-outbound").success(),"outbound replay");
  ok(!b.transferShopReferenced(z,"personal:"+top.id(),d("1"),false,"outbound-short").success(),"outbound no recovery");
  ok(b.rollbackShopPayment("new-outbound").success(),"outbound compensation");
  eq(b.personalHoldingExact(top.id(),z),"0"); eq(b.personalRecovered(bottom.id()),"2.5"); eq(b.systemSettlementBalance("shop","diamond"),"0.25");
  b.checkInvariants();
  ok(b.reversePersonalSettlement("one").success(),"reverse"); ok(b.reversePersonalSettlement("one").success(),"reverse replay");
  eq(b.systemSettlementBalance("shop","diamond"),"0"); eq(b.personalRecovered(bottom.id()),"0"); b.checkInvariants();
  String before=b.toJson(); ok(!b.switchPersonalReserve(z,"personal:"+top.id()).success(),"cycle refused"); ok(before.equals(b.toJson()),"cycle mutated");
  ok(b.settlePersonalToSystem(a,top.id(),d("5"),"shop","two").success(),"second settle");
  ok(b.completeSystemPayment("two").success(),"second delivery");
  ok(b.returnRecoveredPersonal(a,d("5")).success(),"float return"); eq(b.personalActiveExact(top),"100"); eq(b.effectiveBackingExact(top),"47.5");
  ok(!b.reversePersonalSettlement("two").success(),"no reverse released pool"); b.checkInvariants();
  ok(b.switchPersonalReserve(a,"copper").success(),"switch"); ok(b.personalReserveSwitching(top.id()),"pause persisted");
  ok(!b.quotePersonalClearing(top.id(),d("1")).success(),"paused quote");
  eq(b.personalDiamondPrice(top),"0");
  b=BankBooks.fromJson(b.toJson(),BankConfig.defaults()); top=b.personalNote(a); ok(b.personalReserveSwitching(top.id()),"pause after reload");
  b.deposit(a,"copper",BigInteger.ONE); ok(b.depositPersonalReserveExact(a,d("0.5")).success(),"fractional deposit");
  ok(!b.personalReserveSwitching(top.id()),"resumed"); eq(b.effectiveBackingExact(top),"0.5"); b.checkInvariants();
  String unchanged=b.toJson(); ok(!b.settlePersonalToSystem(a,top.id(),d("0.00001"),"shop","bad").success(),"precision"); ok(unchanged.equals(b.toJson()),"invalid mutation");
  // Central collateral is real public liability moved to reserve and back once.
  UUID c=UUID.randomUUID(), publicHolder=UUID.randomUUID(); BankBooks central=BankBooks.create(BankConfig.defaults());
  PersonalNote cash=coin(central,c,"银","note",100);
  central.creditHeld(c,"note",d("10")); ok(central.depositPersonalReserveExact(c,d("10")).success(),"central collateral");
  BigDecimal liability=central.supply("note").liabilityExact();
  ok(central.settlePersonalToSystem(c,cash.id(),d("5"),"land-grant","cash").success(),"central clear");
  eq(central.supply("note").liabilityExact(),liability.add(d("0.5")).toPlainString()); central.checkInvariants();
  ok(central.reversePersonalSettlement("cash").success(),"central reverse"); eq(central.supply("note").liabilityExact(),liability.toPlainString()); central.checkInvariants();
  // Explicit fixed target may run out of issuer funds, never seize the public balance.
  ok(central.transferPersonal(c,publicHolder,"银",BigInteger.valueOf(90)).success(),"public transfer");
  // Fixture: a legacy HARD note already configured before mode-isolated settings.
  cash.adopt(NoteRegime.HARD, cash.anchor(), null);
  ok(central.setPersonalPolicy(c,NoteRegime.HARD,d("1"),d("1"),d("1")).success(),"fixed target");
  ok(central.rebalancePersonalPolicy(c).success(),"fixed rebalance");
  eq(central.personalHoldingExact(cash.id(),publicHolder),"90"); eq(central.personalRecovered(cash.id()),"10");
  eq(central.personalActiveExact(cash),"90"); eq(central.effectiveBackingExact(cash),"10");
  ok(!central.rebalancePersonalPolicy(c).success(),"unchanged rebalance"); central.checkInvariants();
  // Real direct shop funds, idempotent commit and exact compensating debit.
  central.creditHeld(c,"note",d("3.125"));
  ok(central.transferShopReferenced(c,"note",d("0.125"),true,"direct").success(),"direct");
  ok(central.transferShopReferenced(c,"note",d("0.125"),true,"direct").success(),"direct replay");
  eq(central.shopOperatingExact("note"),"0.125");
  ok(central.rollbackShopPayment("direct").success(),"direct rollback");
  ok(central.rollbackShopPayment("direct").success(),"direct rollback replay");
  eq(central.shopOperatingExact("note"),"0"); eq(central.balanceExact(c,"note"),"3.125");
  ok(central.settlementStatus("direct").equals("rolled_back"),"status");
  central=BankBooks.fromJson(central.toJson(),BankConfig.defaults()); central.checkInvariants();
  ok(central.settlementStatus("direct").equals("rolled_back"),"persisted status");
  UUID allOwner=UUID.randomUUID(); BankBooks all=BankBooks.create(BankConfig.defaults());
  PersonalNote allCoin=coin(all,allOwner,"尽","diamond",1); all.deposit(allOwner,"diamond",BigInteger.ONE);
  ok(all.depositPersonalReserveExact(allOwner,d("1")).success(),"all reserve");
  ok(all.transferShopReferenced(allOwner,"personal:"+allCoin.id(),d("1"),true,"all-in").success(),"all recover");
  ok(all.completeSystemPayment("all-in").success(),"all delivery");
  ok(all.quotePersonalFromSystem(allCoin.id(),d("1")).code().equals("no_current_rate"),"no invented rate");
  eq(all.personalSystemCapacity(allCoin.id(),"shop"),"0"); all.checkInvariants();
  System.out.println("PASS nested exact settlement, custody conserved, recovery, conflict/replay, reload, reverse, cycle, fractional switch/pause");
 }
}
