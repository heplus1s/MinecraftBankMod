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
    @Test void actualIssuerOfferAndCashRemainderAreEscrowedThenPaidTogetherAfterReload() {
        Fixture f = fixture(); BankBooks books = f.books;
        JsonObject quote = books.quoteSystemPayout(f.coin, d("10"));
        assertTrue(quote.get("success").getAsBoolean()); assertEquals("3", quote.get("privateAmount").getAsString());
        equal(field(quote,"issuerProceeds").add(field(quote,"centralRemainder")), d("10"));
        BigDecimal reserve = books.effectiveBackingExact(books.personalNote(f.issuer));
        BigDecimal pool = books.savingsPoolExact("note"), m2 = books.centralM2();
        BigInteger issued = books.personalNote(f.issuer).circulation();
        prepare(f, quote, "mixed");
        equal(books.balanceExact(f.issuer,"note"), BigDecimal.ZERO); equal(books.balanceExact(f.player,"note"), BigDecimal.ZERO);
        equal(books.personalHoldingExact(f.coin,f.player), BigDecimal.ZERO);
        equal(books.personalHoldingExact(f.coin,f.issuer), d("7"));
        equal(books.savingsPoolExact("note"), pool); equal(books.centralM2(), m2);
        equal(books.personalActiveExact(books.personalNote(f.issuer)), BigDecimal.TEN);
        assertEquals(issued, books.personalNote(f.issuer).circulation());
        equal(books.effectiveBackingExact(books.personalNote(f.issuer)), reserve); equal(books.personalRecovered(f.coin), BigDecimal.ZERO);
        assertTrue(books.noteHasPending(f.coin)); assertFalse(books.listPersonal(f.issuer,"售",BigInteger.ZERO).success());
        String beforeReplay = books.toJson(); prepare(f, quote, "mixed"); assertEquals(beforeReplay,books.toJson());
        assertEquals("reference_conflict", books.executeSystemPayout(UUID.randomUUID(),f.coin,d("10"),quote.get("fingerprint").getAsString(),"mixed").code());
        books.checkInvariants();
        BankBooks loaded = BankBooks.fromJson(books.toJson(),BankConfig.defaults()); loaded.checkInvariants();
        assertTrue(loaded.completeSystemPayment("mixed").success()); assertTrue(loaded.completeSystemPayment("mixed").success());
        equal(loaded.balanceExact(f.issuer,"note"),field(quote,"issuerProceeds"));
        equal(loaded.balanceExact(f.player,"note"),field(quote,"centralRemainder"));
        equal(loaded.personalHoldingExact(f.coin,f.player),d("3"));
        equal(loaded.savingsPoolExact("note"),pool); equal(loaded.centralM2(),m2);
        equal(loaded.effectiveBackingExact(loaded.personalNote(f.issuer)),reserve); equal(loaded.personalRecovered(f.coin),BigDecimal.ZERO);
        assertEquals(issued,loaded.personalNote(f.issuer).circulation());
        String completed = loaded.toJson(); assertEquals("delivery_completed",loaded.rollbackShopPayment("mixed").code());
        assertEquals(completed,loaded.toJson()); loaded.checkInvariants();
    }
    @Test void pendingRollbackRestoresOnlyItsOriginalAssetsAndOriginalListing() {
        Fixture f=fixture(); JsonObject quote=f.books.quoteSystemPayout(f.coin,d("10"));
        prepare(f,quote,"rollback");
        assertTrue(f.books.rollbackShopPayment("rollback").success()); assertTrue(f.books.rollbackShopPayment("rollback").success());
        equal(f.books.systemAvailableBalance("shop","note"),d("100"));
        equal(f.books.personalHoldingExact(f.coin,f.issuer),BigDecimal.TEN);
        assertEquals(BigInteger.valueOf(3),f.books.personalOffered(f.coin,f.issuer));
        equal(f.books.balanceExact(f.player,"note"),BigDecimal.ZERO); equal(f.books.balanceExact(f.issuer,"note"),BigDecimal.ZERO);
        assertEquals("reference_conflict",f.books.executeSystemPayout(f.player,f.coin,d("10"),quote.get("fingerprint").getAsString(),"rollback").code());
        f.books.checkInvariants();
    }
    @Test void staleOffersOrActualPricesRequireNewConfirmationAndNoFundsAreInvented() {
        Fixture f=fixture(); JsonObject q=f.books.quoteSystemPayout(f.coin,d("10"));
        f.books.listPersonal(f.issuer,"售",BigInteger.ONE); String before=f.books.toJson();
        assertEquals("quote_changed",f.books.executeSystemPayout(f.player,f.coin,d("10"),q.get("fingerprint").getAsString(),"stale-offer").code());
        assertEquals(before,f.books.toJson());
        JsonObject price=f.books.quoteSystemPayout(f.coin,d("10"));
        f.books.deposit(f.issuer,"diamond",BigInteger.ONE); f.books.depositPersonalReserveExact(f.issuer,BigDecimal.ONE); before=f.books.toJson();
        assertEquals("quote_changed",f.books.executeSystemPayout(f.player,f.coin,d("10"),price.get("fingerprint").getAsString(),"stale-price").code());
        assertEquals(before,f.books.toJson());
        JsonObject tooLarge=f.books.quoteSystemPayout(f.coin,d("101"));
        assertEquals("insufficient_system",f.books.executeSystemPayout(f.player,f.coin,d("101"),tooLarge.get("fingerprint").getAsString(),"no-cash").code());
        assertEquals(before,f.books.toJson());
    }
    @Test void issuerAlsoBeingTheGoodsSellerDoesNotReceiveDuplicatedCurrencyOrCash() {
        Fixture f=fixture(); JsonObject q=f.books.quoteSystemPayout(f.coin,d("10"));
        assertTrue(f.books.executeSystemPayout(f.issuer,f.coin,d("10"),q.get("fingerprint").getAsString(),"self").success());
        assertTrue(f.books.completeSystemPayment("self").success());
        equal(f.books.personalHoldingExact(f.coin,f.issuer),BigDecimal.TEN);
        equal(f.books.balanceExact(f.issuer,"note"),d("10"));
        equal(f.books.systemAvailableBalance("shop","note"),d("90")); f.books.checkInvariants();
    }
    @Test void cashFallbackAndLandPayoutStayWithinTheirOwnRealAccounts() {
        Fixture f=fixture(); f.books.listPersonal(f.issuer,"售",BigInteger.ZERO);
        JsonObject q=f.books.quoteSystemPayout("land",f.coin,d("1.25"));
        assertEquals("0",q.get("privateAmount").getAsString()); equal(field(q,"centralRemainder"),d("1.25"));
        JsonObject other=f.books.quoteSystemPayout("shop",f.coin,d("1.25"));
        assertNotEquals(other.get("fingerprint").getAsString(),q.get("fingerprint").getAsString());
        assertEquals("quote_changed",f.books.executeSystemPayout("land",f.player,f.coin,d("1.25"),other.get("fingerprint").getAsString(),"wrong-account").code());
        BigDecimal pool=f.books.savingsPoolExact("note");
        assertTrue(f.books.executeSystemPayout("land",f.player,f.coin,d("1.25"),q.get("fingerprint").getAsString(),"land").success());
        equal(f.books.landGrantExact("note"),d("18.75")); equal(f.books.systemAvailableBalance("shop","note"),d("100"));
        equal(f.books.savingsPoolExact("note"),pool); f.books.checkInvariants();
        BankBooks loaded=BankBooks.fromJson(f.books.toJson(),BankConfig.defaults());
        assertTrue(loaded.completeSystemPayment("land").success()); equal(loaded.landGrantExact("note"),d("18.75"));
        equal(loaded.balanceExact(f.player,"note"),d("1.25")); equal(loaded.systemAvailableBalance("shop","note"),d("100"));
        equal(loaded.savingsPoolExact("note"),pool); loaded.checkInvariants();
    }
}
