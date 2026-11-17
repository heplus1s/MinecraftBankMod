package dev.jdguo.bank.market;

import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutoListingTest {
    final UUID issuer = UUID.randomUUID(), buyer = UUID.randomUUID(), other = UUID.randomUUID();
    BankBooks fixture() {
        BankBooks b = BankBooks.create(BankConfig.defaults());
        assertTrue(b.createPersonalNote(issuer, "泉", NoteRegime.FLOAT, "diamond", BigInteger.ONE).success());
        assertTrue(b.deposit(issuer, "diamond", BigInteger.TEN).success());
        assertTrue(b.depositPersonalReserve(issuer, BigInteger.TEN).success());
        assertTrue(b.issuePersonalNoteUnbacked(issuer, BigInteger.TEN).success());
        b.creditHeld(buyer, "note", BigInteger.valueOf(1000));
        return b;
    }
    @Test void defaultInventoryTracksIssuanceTransferAndPurchaseWithoutLockingOrReissuing() {
        BankBooks b=fixture(); var id=b.personalNote(issuer).id();
        assertEquals(BigInteger.TEN,b.personalOffered(id,issuer));
        assertEquals(BigInteger.TEN,b.availablePersonalOffer(id,buyer));
        assertTrue(b.transferPersonal(issuer,other,"泉",BigInteger.TWO).success());
        assertEquals(BigInteger.valueOf(8),b.personalOffered(id,issuer));
        assertEquals(BigInteger.ZERO,b.personalOffered(id,other));
        assertTrue(b.issuePersonalNoteUnbacked(issuer,BigInteger.TWO).success());
        assertEquals(BigInteger.TEN,b.personalOffered(id,issuer));
        var issued=b.personalNote(issuer).circulation();
        assertTrue(b.buyListedPersonal(buyer,"泉",BigInteger.ONE,new BigDecimal("1000")).success());
        assertEquals(issued,b.personalNote(issuer).circulation());
        assertEquals(b.personalHolding(id,issuer),b.personalOffered(id,issuer));
        assertEquals(BigInteger.ZERO,b.personalOffered(id,buyer));
        b.checkInvariants();
    }
    @Test void offCancelsManualAndAutomaticOffersButNeverOtherHoldersAndPersists() {
        BankBooks b=fixture(); var id=b.personalNote(issuer).id();
        b.transferPersonal(issuer,other,"泉",BigInteger.TWO);
        b.listPersonal(other,"泉",BigInteger.ONE);
        assertFalse(b.setPersonalAutoListing(other,"泉",false).success());
        assertTrue(b.setPersonalAutoListing(issuer,"泉",false).success());
        assertEquals(BigInteger.ZERO,b.personalOffered(id,issuer));
        assertFalse(b.buyPersonalNote(buyer,"泉",BigInteger.ONE).success());
        b.issuePersonalNoteUnbacked(issuer,BigInteger.ONE);
        b=BankBooks.fromJson(b.toJson(),BankConfig.defaults());
        assertFalse(b.personalNote(issuer).autoListing());
        assertEquals(BigInteger.ZERO,b.personalOffered(id,issuer));
        assertEquals(BigInteger.ONE,b.personalOffered(id,other));
        b.listPersonal(issuer,"泉",BigInteger.TWO);
        assertFalse(b.personalNote(issuer).autoListing());
        assertEquals(BigInteger.TWO,b.personalOffered(id,issuer));
        b.setPersonalAutoListing(issuer,"泉",false);
        assertEquals(BigInteger.ZERO,b.personalOffered(id,issuer));
        b.setPersonalAutoListing(issuer,"泉",true);
        assertEquals(b.personalHolding(id,issuer),b.personalOffered(id,issuer));
        b.checkInvariants();
    }
    @Test void missingLegacyFlagDefaultsOnWithoutCreatingAnyCoins() {
        BankBooks b=fixture(); var json=JsonParser.parseString(b.toJson()).getAsJsonObject();
        // Strip the field wherever series are stored, simulating a pre-switch save.
        String old=json.toString().replace(",\"autoListing\":true","");
        BankBooks loaded=BankBooks.fromJson(old,BankConfig.defaults());
        assertTrue(loaded.personalNote(issuer).autoListing());
        assertEquals(BigInteger.TEN,loaded.personalOffered(loaded.personalNote(issuer).id(),issuer));
        assertEquals(b.personalNote(issuer).circulation(),loaded.personalNote(issuer).circulation());
        loaded.checkInvariants();
    }
    @Test void pendingPayoutCannotToggleAndRollbackReturnsOnlyEscrowedInventory() {
        BankBooks b=fixture(); var id=b.personalNote(issuer).id();
        b.creditHeld(other,"note",BigInteger.valueOf(100));
        b.transferShopOperating(other,BigInteger.valueOf(100),true);
        var q=b.quoteSystemPayout(id,new BigDecimal("3"));
        assertTrue(b.executeSystemPayout(buyer,id,new BigDecimal("3"),q.get("fingerprint").getAsString(),"auto").success());
        assertEquals("delivery_pending",b.setPersonalAutoListing(issuer,"泉",false).code());
        BankBooks loaded=BankBooks.fromJson(b.toJson(),BankConfig.defaults());
        assertTrue(loaded.rollbackShopPayment("auto").success());
        assertEquals(BigInteger.TEN,loaded.personalHolding(id,issuer));
        assertEquals(BigInteger.TEN,loaded.personalOffered(id,issuer));
        var snapshot=loaded.toJson();
        loaded.rollbackShopPayment("auto");
        assertEquals(snapshot,loaded.toJson());
        loaded.checkInvariants();
    }
    @Test void fractionalBalancesOnlyExposeWholeTradeableCoinsAndReturnedCoinsRelist() {
        BankBooks b=fixture(); var id=b.personalNote(issuer).id();
        assertTrue(b.transferPersonalExact(issuer,other,"personal:"+id,new BigDecimal("0.6")).success());
        assertEquals(BigInteger.valueOf(9),b.personalOffered(id,issuer));
        assertTrue(b.transferPersonalExact(other,issuer,"personal:"+id,new BigDecimal("0.6")).success());
        assertEquals(BigInteger.TEN,b.personalOffered(id,issuer));
        assertEquals(BigDecimal.ZERO.setScale(4),b.personalHoldingExact(id,other));
        b.checkInvariants();
    }
    @Test void swapNeverTakesUnlistedInventoryAndAutomaticInventoryCanBeRedeemed() {
        BankBooks b=fixture(); var id=b.personalNote(issuer).id();
        b.createPersonalNote(buyer,"购",NoteRegime.FLOAT,"diamond",BigInteger.ONE);
        b.issuePersonalNoteUnbacked(buyer,BigInteger.TEN);
        b.deposit(buyer,"diamond",BigInteger.TEN);
        b.depositPersonalReserve(buyer,BigInteger.TEN);
        b.setPersonalAutoListing(issuer,"泉",false);
        assertFalse(b.swapPersonalNotes(buyer,"购","泉",BigInteger.ONE).success());
        b.setPersonalAutoListing(issuer,"泉",true);
        assertTrue(b.redeemPersonalToSavings(issuer,id,new BigDecimal("0.5"),"auto-redeem").success());
        assertEquals(BigInteger.valueOf(9),b.personalOffered(id,issuer));
        b.checkInvariants();
    }
}
