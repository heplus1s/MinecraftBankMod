package dev.jdguo.bank.ledger;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class FeeTreasuryTest {
 private final BankConfig config=BankConfig.defaults();
 private void eq(String value, BigDecimal actual){assertEquals(0,new BigDecimal(value).compareTo(actual));}
 @Test void feesAreOwnedAssetsNotCustodyAndInterestOnlyTransfers(){
  BankBooks b=BankBooks.create(config); UUID u=UUID.randomUUID();
  b.creditHeld(u,"note",new BigInteger("36500"));
  b.postRetainedFee("note",BigInteger.TEN);
  eq("10",b.treasuryExact("note")); eq("36500",b.savingsPoolExact("note"));
  BigDecimal l=b.supply("note").liabilityExact();
  b.accrueInterest(0); b.accrueInterest(1);
  eq("8",b.treasuryExact("note")); eq("36502",b.balanceExact(u,"note"));
  assertEquals(l,b.supply("note").liabilityExact()); b.checkInvariants();
 }
 @Test void legacyFeesMigrateOnceWithSupplyAccounting(){
  BankBooks b=BankBooks.create(config); JsonObject old=JsonParser.parseString(b.toJson()).getAsJsonObject();
  old.remove("feeTreasuryVersion");
  old.getAsJsonObject("interestPool").addProperty("note","12.3456");
  old.getAsJsonObject("interestPool").addProperty("copper","0.1234");
  BigDecimal l=b.supply("note").liabilityExact();
  BankBooks a=BankBooks.fromJson(old.toString(),config);
  eq("12.3456",a.treasuryExact("note")); eq("0.1234",a.treasuryExact("copper"));
  assertEquals(0,l.add(new BigDecimal("12.3456")).compareTo(a.supply("note").liabilityExact()));
  assertEquals(b.supply("copper").depositVaultExact(),a.supply("copper").depositVaultExact());
  String stable=a.toJson(); assertEquals(stable,BankBooks.fromJson(stable,config).toJson());
  assertEquals(stable,a.copyExact().toJson()); a.checkInvariants();
 }
 @Test void budgetDoesNotSpendOtherAssetsWhenTreasuryEmpty(){
  BankBooks b=BankBooks.create(config); UUID u=UUID.randomUUID();
  b.creditHeld(u,"note",new BigInteger("36500")); b.postRetainedFee("note",BigInteger.TEN);
  JsonObject state=JsonParser.parseString(b.toJson()).getAsJsonObject(); state.getAsJsonObject("centralTreasury").addProperty("note","0");
  b=BankBooks.fromJson(state.toString(),config); BigDecimal l=b.supply("note").liabilityExact();
  b.accrueInterest(0); b.accrueInterest(1);
  eq("36500",b.balanceExact(u,"note")); eq("10",b.interestPoolExact("note"));
  assertEquals(l,b.supply("note").liabilityExact());
 }
 @Test void actualRedemptionRetainsFeeAndOnlyBurnsPrincipal(){
  BankBooks b=BankBooks.create(config); UUID u=UUID.randomUUID();
  b.creditHeld(u,"note",new BigInteger("100"));
  b.supply("diamond").addReserve(new BigInteger("100"));
  BigDecimal before=b.supply("note").liabilityExact();
  var q=b.quoteHardExchange("note","diamond",BigInteger.ONE);
  assertNotNull(q);
  assertTrue(b.confirmHardExchange(u,q).success());
  BigDecimal principal=q.rate().multiply(q.credit()).setScale(4,RoundingMode.DOWN);
  assertEquals(q.debit().subtract(principal), b.treasuryExact("note"));
  assertEquals(b.treasuryExact("note"), b.interestPoolExact("note"));
  assertEquals(0,before.subtract(principal).compareTo(b.supply("note").liabilityExact()));
  b.checkInvariants();
 }
}
