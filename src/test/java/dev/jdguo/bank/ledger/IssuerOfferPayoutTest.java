package dev.jdguo.bank.ledger;

import com.google.gson.JsonObject;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.NoteRegime;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class IssuerOfferPayoutTest {
    private static BigDecimal d(String value) { return new BigDecimal(value); }
    private static void equal(BigDecimal actual, BigDecimal wanted) { assertEquals(0, actual.compareTo(wanted), actual + " != " + wanted); }
    private static BigDecimal field(JsonObject quote, String key) { return new BigDecimal(quote.get(key).getAsString()); }
    private record Fixture(BankBooks books, UUID issuer, UUID player, UUID donor, UUID coin) {}
    private Fixture fixture() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID issuer = UUID.randomUUID(), player = UUID.randomUUID(), donor = UUID.randomUUID();
        books.creditHeld(donor, "note", new BigInteger("2000"));
        assertTrue(books.transferShopOperating(donor, new BigInteger("100"), true).success());
        assertTrue(books.creditLandGrant(donor, "note", new BigInteger("20")).success());
        assertTrue(books.createPersonalNote(issuer, "售", NoteRegime.FLOAT, "diamond", BigInteger.ONE).success());
        assertTrue(books.issuePersonalNoteUnbacked(issuer, BigInteger.TEN).success());
        assertTrue(books.deposit(issuer, "diamond", BigInteger.TEN).success());
        assertTrue(books.depositPersonalReserveExact(issuer, BigDecimal.TEN).success());
        assertTrue(books.listPersonal(issuer, "售", BigInteger.valueOf(3)).success());
        return new Fixture(books, issuer, player, donor, books.personalNote(issuer).id());
    }
    private static void prepare(Fixture f, JsonObject q, String ref) {
        TxResult result = f.books.executeSystemPayout(f.player, f.coin, field(q,"centralBudget"), q.get("fingerprint").getAsString(), ref);
        assertTrue(result.success(), result.code());
    }
    @Test void fractionalIssuerOfferIsEscrowedAndPaidAfterReloadWithoutCashChange() {
        Fixture f = fixture(); BankBooks b = f.books;
        JsonObject q = b.quoteSystemPayout(f.coin, d("1.25"));
        assertTrue(q.get("success").getAsBoolean());
        BigDecimal qty = field(q,"privateAmount"), pool = b.savingsPoolExact("note"), m2 = b.centralM2();
        assertTrue(qty.remainder(BigDecimal.ONE).signum() > 0);
        equal(field(q,"centralRemainder"), BigDecimal.ZERO);
        equal(field(q,"issuerProceeds"), d("1.25"));
        BigDecimal reserve = b.effectiveBackingExact(b.personalNote(f.issuer));
        prepare(f,q,"fraction");
        equal(b.personalHoldingExact(f.coin,f.issuer),BigDecimal.TEN.subtract(qty));
        equal(b.personalHoldingExact(f.coin,f.player),BigDecimal.ZERO);
        equal(b.personalActiveExact(b.personalNote(f.issuer)),BigDecimal.TEN);
        equal(b.savingsPoolExact("note"),pool); equal(b.centralM2(),m2);
        String pending=b.toJson(); prepare(f,q,"fraction"); assertEquals(pending,b.toJson());
        BankBooks loaded=BankBooks.fromJson(pending,BankConfig.defaults()); loaded.checkInvariants();
        assertTrue(loaded.completeSystemPayment("fraction").success()); assertTrue(loaded.completeSystemPayment("fraction").success());
        equal(loaded.personalHoldingExact(f.coin,f.player),qty);
        equal(loaded.balanceExact(f.player,"note"),BigDecimal.ZERO);
        equal(loaded.balanceExact(f.issuer,"note"),d("1.25"));
        equal(loaded.systemAvailableBalance("shop","note"),d("98.75"));
        equal(loaded.savingsPoolExact("note"),pool); equal(loaded.centralM2(),m2);
        equal(loaded.effectiveBackingExact(loaded.personalNote(f.issuer)),reserve);
        assertEquals("delivery_completed",loaded.rollbackShopPayment("fraction").code()); loaded.checkInvariants();
    }
    @Test void fractionalRollbackRestoresManualOfferAndExactHoldings() {
        Fixture f=fixture(); JsonObject q=f.books.quoteSystemPayout(f.coin,d("1.25")); prepare(f,q,"reverse");
        BankBooks b=BankBooks.fromJson(f.books.toJson(),BankConfig.defaults());
        assertTrue(b.rollbackShopPayment("reverse").success()); assertTrue(b.rollbackShopPayment("reverse").success());
        equal(b.personalHoldingExact(f.coin,f.issuer),BigDecimal.TEN);
        assertEquals(BigInteger.valueOf(3),b.personalOffered(f.coin,f.issuer));
        equal(b.systemAvailableBalance("shop","note"),d("100"));
        equal(b.balanceExact(f.player,"note"),BigDecimal.ZERO); b.checkInvariants();
    }
    @Test void staleQuoteAndInsufficientOffersNeverFallbackOrMutateAssets() {
        Fixture f=fixture(); JsonObject q=f.books.quoteSystemPayout(f.coin,d("1.25"));
        f.books.listPersonal(f.issuer,"售",BigInteger.valueOf(2)); String before=f.books.toJson();
        assertEquals("quote_changed",f.books.executeSystemPayout(f.player,f.coin,d("1.25"),q.get("fingerprint").getAsString(),"stale").code());
        assertEquals(before,f.books.toJson());
        JsonObject large=f.books.quoteSystemPayout(f.coin,d("10")); assertFalse(large.get("success").getAsBoolean());
        assertEquals("insufficient_offer",large.get("code").getAsString());
        f.books.listPersonal(f.issuer,"售",BigInteger.ZERO); before=f.books.toJson();
        JsonObject noOffer=f.books.quoteSystemPayout(f.coin,d("1.25")); assertFalse(noOffer.get("success").getAsBoolean());
        assertFalse(f.books.executeSystemPayout(f.player,f.coin,d("1.25"),q.get("fingerprint").getAsString(),"absent").success());
        assertEquals(before,f.books.toJson());
    }
    @Test void quoteAboveCashFailsAtExecutionAndCapacityIsNotWholeBudgetQuote() {
        Fixture f=fixture();
        equal(f.books.systemPersonalPayoutCapacity("shop",f.coin),d("3"));
        JsonObject q=f.books.quoteSystemPayout(f.coin,d("1.25"));
        assertTrue(f.books.transferShopOperating(f.donor,BigInteger.valueOf(100),false).success());
        String before=f.books.toJson();
        assertEquals("insufficient_system",f.books.executeSystemPayout(f.player,f.coin,d("1.25"),q.get("fingerprint").getAsString(),"cash").code());
        assertEquals(before,f.books.toJson()); equal(f.books.systemPersonalPayoutCapacity("shop",f.coin),BigDecimal.ZERO);
    }
    @Test void selfPayoutAndLandAccountRemainConserved() {
        Fixture f=fixture(); JsonObject q=f.books.quoteSystemPayout("land",f.coin,d("1.25"));
        JsonObject shop=f.books.quoteSystemPayout("shop",f.coin,d("1.25"));
        assertEquals("quote_changed",f.books.executeSystemPayout("land",f.player,f.coin,d("1.25"),shop.get("fingerprint").getAsString(),"wrong").code());
        assertTrue(f.books.executeSystemPayout("land",f.issuer,f.coin,d("1.25"),q.get("fingerprint").getAsString(),"land").success());
        assertTrue(f.books.completeSystemPayment("land").success());
        equal(f.books.personalHoldingExact(f.coin,f.issuer),BigDecimal.TEN);
        equal(f.books.balanceExact(f.issuer,"note"),d("1.25"));
        equal(f.books.landGrantExact("note"),d("18.75"));
        equal(f.books.systemAvailableBalance("shop","note"),d("100")); f.books.checkInvariants();
    }
    @Test void autoOfferIncludesFractionalInventoryAndRollbackDoesNotMint() {
        Fixture f=fixture(); assertTrue(f.books.setPersonalAutoListing(f.issuer,"售",true).success());
        JsonObject q=f.books.quoteSystemPayout(f.coin,d("9.5")); prepare(f,q,"auto");
        assertTrue(f.books.completeSystemPayment("auto").success());
        BigDecimal left=f.books.personalHoldingExact(f.coin,f.issuer); assertTrue(left.compareTo(BigDecimal.ONE)<0);
        assertTrue(f.books.systemPersonalPayoutCapacity("shop",f.coin).signum()>0);
        JsonObject next=f.books.quoteSystemPayout(f.coin,d("0.25")); assertTrue(next.get("success").getAsBoolean());
        prepare(f,next,"tiny"); assertTrue(f.books.rollbackShopPayment("tiny").success());
        equal(f.books.personalHoldingExact(f.coin,f.issuer),left); f.books.checkInvariants();
    }
    private static String legacyMixedPending(Fixture f) {
        JsonObject q=f.books.quoteSystemPayout(f.coin,d("1.005"));
        equal(field(q,"privateAmount"),BigDecimal.ONE); prepare(f,q,"legacy");
        JsonObject root=com.google.gson.JsonParser.parseString(f.books.toJson()).getAsJsonObject();
        root.getAsJsonObject("shopOperating").addProperty("note","98");
        root.getAsJsonObject("shopFraction").addProperty("note","0.75");
        JsonObject receipt=root.getAsJsonObject("recursiveClearing").getAsJsonObject("receipts").getAsJsonObject("legacy");
        receipt.remove("listingReserved"); receipt.addProperty("privateAmount","1");
        for(String key:new String[]{"centralBudget","amount","fromOrdinary"}) receipt.addProperty(key,"1.25");
        receipt.addProperty("centralRemainder","0.245");
        return root.toString();
    }
    @Test void oldMixedPendingReceiptsStillCompleteAndRollbackExactly() {
        Fixture f=fixture(); String json=legacyMixedPending(f);
        BankBooks complete=BankBooks.fromJson(json,BankConfig.defaults()); complete.checkInvariants();
        assertTrue(complete.completeSystemPayment("legacy").success());
        equal(complete.personalHoldingExact(f.coin,f.player),BigDecimal.ONE);
        equal(complete.balanceExact(f.player,"note"),d("0.245"));
        equal(complete.balanceExact(f.issuer,"note"),d("1.005")); complete.checkInvariants();
        BankBooks reverse=BankBooks.fromJson(json,BankConfig.defaults());
        assertTrue(reverse.rollbackShopPayment("legacy").success());
        equal(reverse.systemAvailableBalance("shop","note"),d("100"));
        equal(reverse.personalHoldingExact(f.coin,f.issuer),BigDecimal.TEN);
        assertEquals(BigInteger.valueOf(3),reverse.personalOffered(f.coin,f.issuer)); reverse.checkInvariants();
    }
    @Test void unrepresentableFractionIsRejectedRatherThanSilentlyPayingNotes() {
        Fixture f=fixture();
        String before=f.books.toJson(); JsonObject q=f.books.quoteSystemPayout(f.coin,d("0.0001"));
        assertFalse(q.get("success").getAsBoolean()); assertEquals("precision",q.get("code").getAsString());
        assertEquals(before,f.books.toJson());
    }
    @Test void fractionalReadCapacityUsesRealNoteBudgetNotPendingPhysicalProceeds() {
        BankBooks b=BankBooks.create(BankConfig.defaults());UUID issuer=UUID.randomUUID(), recipient=UUID.randomUUID();
        b.creditHeld(issuer,"note",d("1")); b.transferShop(issuer,"note",d("0.06"),true);
        assertTrue(b.createPersonalNote(issuer,"Tst",false,BigInteger.ONE).success());UUID coin=b.personalNote(issuer).id();
        b.deposit(issuer,"diamond",BigInteger.valueOf(3));b.depositPersonalReserveExact(issuer,d("3"));
        b.issuePersonalNoteUnbacked(issuer,BigInteger.TWO);
        assertTrue(b.transferShopReferenced(issuer,"personal:"+coin,d("1.5"),true,"capacity-sale").success());
        equal(b.systemPersonalPayoutCapacity("shop",coin),BigDecimal.ZERO);
        equal(b.systemAvailableBalance("shop","note"),d("0.06"));
        assertTrue(b.completeSystemPayment("capacity-sale").success());
        equal(b.systemPersonalPayoutCapacity("shop",coin),d("0.5"));
        equal(b.systemAvailableBalance("shop","note"),d("0.06"));
        equal(b.systemSettlementBalance("shop","diamond"),d("2.25"));
        String before=b.toJson();JsonObject rejected=b.quoteSystemPayout(coin,d("0.05"));
        assertFalse(rejected.get("success").getAsBoolean());assertEquals("insufficient_offer",rejected.get("code").getAsString());
        assertEquals(before,b.toJson());
        b=BankBooks.fromJson(before,BankConfig.defaults());
        equal(b.systemPersonalPayoutCapacity("shop",coin),d("0.5"));
        JsonObject q=b.quoteSystemPayout(coin,d("0.0001"));assertTrue(q.get("success").getAsBoolean(),q.toString());
        BigDecimal qty=d(q.get("privateAmount").getAsString());assertTrue(qty.signum()>0 && qty.compareTo(d("0.5"))<0);
        assertTrue(b.executeSystemPayout(recipient,coin,d("0.0001"),q.get("fingerprint").getAsString(),"capacity-pay").success());
        equal(b.systemPersonalPayoutCapacity("shop",coin),BigDecimal.ZERO);
        equal(b.personalHoldingExact(coin,recipient),BigDecimal.ZERO);
        b=BankBooks.fromJson(b.toJson(),BankConfig.defaults());assertTrue(b.completeSystemPayment("capacity-pay").success());
        equal(b.personalHoldingExact(coin,recipient),qty);equal(b.personalHoldingExact(coin,issuer),d("0.5").subtract(qty));
        equal(b.balanceExact(recipient,"note"),BigDecimal.ZERO);equal(b.balanceExact(issuer,"note"),d("0.9401"));
        equal(b.systemAvailableBalance("shop","note"),d("0.0599"));equal(b.systemSettlementBalance("shop","diamond"),d("2.25"));
        b.checkInvariants();
    }
}
