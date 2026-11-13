package dev.jdguo.bank.market;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.instruments.PresentedNote;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.text.BankPhrases;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankruptcyTest {
    private final UUID issuer = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID buyer = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID stranger = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void bankruptcyRejectsQuoteTradeAndRedemptionButKeepsVoidHoldings() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setPersonalReserve(issuer, "diamond", BigInteger.TEN);
        assertTrue(books.createPersonalNote(issuer, "Aa", false, BigInteger.ONE).success());
        assertTrue(books.issuePersonalNote(issuer, BigInteger.TEN).success());
        books.creditUnbacked(buyer, "note", new BigInteger("20"));
        books.deposit(buyer, "copper", new BigInteger("1000"));
        assertTrue(books.buyPersonalNote(buyer, "Aa", new BigInteger("4")).success());
        assertTrue(books.issuePersonalPaper(buyer, books.personalNote(issuer).id(), BigInteger.ONE).result().success());

        PersonalNote note = books.personalNote(issuer);
        BigInteger circulation = note.circulation();
        BigInteger buys = note.buys();
        BigInteger sells = note.sells();
        BigInteger locked = note.locked();
        BigInteger issuerHolding = books.personalHolding(note.id(), issuer);
        BigInteger buyerHolding = books.personalHolding(note.id(), buyer);
        BigInteger paper = books.paperOutstanding(null, note.id(), BigInteger.ONE);
        BigInteger reserve = books.personalReserve(issuer, "diamond");
        assertTrue(books.personalDiamondPrice(note).signum() > 0);

        NoteTrade closed = books.declareBankruptcy(issuer, null, false);

        assertTrue(closed.success(), closed.code());
        assertTrue(note.bankrupt());
        assertEquals(locked, note.released());
        assertEquals(BigInteger.ZERO, note.locked());
        assertEquals(reserve, books.personalReserve(issuer, "diamond"));
        assertEquals(circulation, note.circulation());
        assertEquals(buys, note.buys());
        assertEquals(sells, note.sells());
        assertEquals("Aa", note.name());
        assertEquals(BigInteger.ONE, note.par());
        assertEquals(issuerHolding, books.personalHolding(note.id(), issuer));
        assertEquals(buyerHolding, books.personalHolding(note.id(), buyer));
        assertEquals(paper, books.paperOutstanding(null, note.id(), BigInteger.ONE));
        assertEquals(0, BigDecimal.ZERO.compareTo(books.personalDiamondPrice(note)));
        assertTrue(BankPhrases.noteRate(books, note).zh().contains("不再报价"));

        assertEquals("bankrupt", books.buyPersonalNote(buyer, "Aa", BigInteger.ONE).code());
        assertEquals("bankrupt", books.sellPersonalNote(buyer, "Aa", BigInteger.ONE).code());
        assertEquals("bankrupt", books.issuePersonalNote(issuer, BigInteger.ONE).code());
        assertEquals("bankrupt", books.redeemPersonal(buyer, "Aa", BigInteger.ONE, "diamond").code());
        assertEquals("bankrupt", books.issuePersonalPaper(issuer, note.id(), BigInteger.ONE).result().code());
        TxResult deposited = books.redeemPaper(buyer, new PresentedNote(null, note.id(), BigInteger.ONE), BigInteger.ONE);
        assertEquals("bankrupt", deposited.code());
        assertEquals(paper, books.paperOutstanding(null, note.id(), BigInteger.ONE));
        assertEquals(buyerHolding, books.personalHolding(note.id(), buyer));
        assertEquals("closed", books.declareBankruptcy(issuer, "Aa", false).code());
        assertEquals("denied", books.declareBankruptcy(stranger, "Aa", false).code());

        BankBooks loaded = BankBooks.fromJson(books.toJson(), BankConfig.defaults());
        PersonalNote saved = loaded.personalNote(issuer);
        assertTrue(saved.bankrupt());
        assertEquals(locked, saved.released());
        assertEquals(circulation, saved.circulation());
        assertEquals(buys, saved.buys());
        assertEquals(buyerHolding, loaded.personalHolding(saved.id(), buyer));
        assertEquals("bankrupt", loaded.swapPersonalNotes(buyer, "Aa", "Aa", BigInteger.ONE).code());
        loaded.checkInvariants();
        books.checkInvariants();
    }
}
