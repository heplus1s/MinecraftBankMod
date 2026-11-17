package dev.jdguo.bank.market;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalOffersTest {
    private static final BigInteger ONE = BigInteger.ONE;
    private final UUID issuer = UUID.randomUUID();
    private final UUID holder = UUID.randomUUID();
    private final UUID buyer = UUID.randomUUID();

    @Test
    void transferAndListingKeepQuantityAndSettleOnlyWithConsent() {
        BankBooks books = fixture(BigInteger.valueOf(8));
        PersonalNote note = books.personalNote(issuer);
        BigInteger four = BigInteger.valueOf(4);
        assertTrue(books.setPersonalAutoListing(issuer, "泉", false).success());
        assertEquals("offer_empty", books.buyListedPersonal(buyer, "泉", ONE, BigDecimal.TEN).code());
        assertTrue(books.transferPersonal(issuer, holder, "泉", four).success());
        assertTrue(books.listPersonal(holder, "泉", BigInteger.TWO).success());
        assertEquals("unlisted_funds", books.transferPersonal(holder, buyer, "泉", four).code());
        assertEquals("offer_price_moved", books.buyListedPersonal(buyer, "泉", ONE, new BigDecimal("0.0001")).code());
        assertEquals("funds", books.buyListedPersonal(buyer, "泉", ONE, BigDecimal.TEN).code());

        BankBooks restored = BankBooks.fromJson(books.toJson(), BankConfig.defaults());
        restored.creditHeld(buyer, "note", BigInteger.TEN);
        BigDecimal buyerBefore = restored.balanceExact(buyer, "note");
        BigDecimal sellerBefore = restored.balanceExact(holder, "note");
        assertTrue(restored.quoteListedPersonal(buyer, "泉", ONE).success());
        assertEquals(buyerBefore, restored.balanceExact(buyer, "note"));
        assertTrue(restored.buyListedPersonal(buyer, "泉", ONE, BigDecimal.TEN).success());
        assertEquals(ONE, restored.personalHolding(note.id(), buyer));
        assertEquals(ONE, restored.personalOffered(note.id(), holder));
        BigDecimal payment = buyerBefore.subtract(restored.balanceExact(buyer, "note"));
        assertTrue(payment.signum() > 0);
        assertEquals(0, payment.compareTo(restored.balanceExact(holder, "note").subtract(sellerBefore)));
        assertEquals(BigInteger.ZERO, restored.balance(buyer, "copper"));
        assertEquals(note.circulation(), restored.personalNote(issuer).circulation());
        assertTrue(restored.listPersonal(holder, "泉", BigInteger.ZERO).success());
        assertEquals("offer_empty", restored.quoteListedPersonal(buyer, "泉", ONE).code());
    }

    @Test
    void subMinimumOrderNeverTransfersCurrencyOrPersonalNotes() {
        BankBooks books = fixture(BigInteger.valueOf(2_000_000));
        PersonalNote note = books.personalNote(issuer);
        assertTrue(books.listPersonal(issuer, "泉", ONE).success());
        assertEquals("offer_minimum", books.buyListedPersonal(buyer, "泉", ONE, BigDecimal.TEN).code());
        assertEquals(BigInteger.ZERO, books.personalHolding(note.id(), buyer));
        assertEquals(ONE, books.personalOffered(note.id(), issuer));
    }

    private BankBooks fixture(BigInteger issued) {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditHeld(issuer, "note", BigInteger.valueOf(2_000));
        assertTrue(books.createPersonalNote(issuer, "泉", NoteRegime.FLOAT, "diamond", ONE).success());
        assertTrue(books.deposit(issuer, "diamond", ONE).success());
        assertTrue(books.depositPersonalReserve(issuer, ONE).success());
        assertTrue(books.issuePersonalNoteUnbacked(issuer, issued).success());
        return books;
    }
}
