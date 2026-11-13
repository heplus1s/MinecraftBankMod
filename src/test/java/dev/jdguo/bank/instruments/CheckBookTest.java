package dev.jdguo.bank.instruments;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.TxResult;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckBookTest {
    private final UUID issuer = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final UUID holder = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private final UUID stranger = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Test
    void checkMovesBalanceAndCanBeRedeemedOnce() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(issuer, "note", BigInteger.valueOf(500));
        BigInteger liability = books.supply("note").liability();

        TxResult issued = books.issueCheck(issuer, "note", BigInteger.valueOf(200), "Steve", null, true);

        assertTrue(issued.success());
        assertEquals(BigInteger.valueOf(300), books.balance(issuer, "note"));
        assertEquals(liability, books.supply("note").liability());

        PresentedCheck copy = new PresentedCheck(issued.instrumentId(), "note", BigInteger.valueOf(200), issuer, null);
        TxResult first = books.redeemCheck(holder, copy);
        TxResult second = books.redeemCheck(stranger, copy);

        assertTrue(first.success());
        assertEquals(BigInteger.ZERO, books.balance(holder, "note"));
        assertTrue(books.balance(holder, "diamond").signum() > 0 || books.balance(holder, "copper").signum() > 0);
        assertFalse(second.success());
        assertEquals("already_redeemed", second.code());
        assertEquals(BigInteger.ZERO, books.balance(stranger, "note"));
        books.checkInvariants();
    }

    @Test
    void tamperedAmountDoesNotPay() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(issuer, "note", BigInteger.valueOf(50));
        TxResult issued = books.issueCheck(issuer, "note", BigInteger.valueOf(50), "Steve", null, true);
        PresentedCheck tampered = new PresentedCheck(issued.instrumentId(), "note", new BigInteger("999999"), issuer, null);

        TxResult result = books.redeemCheck(holder, tampered);

        assertEquals("tampered", result.code());
        assertEquals(BigInteger.ZERO, books.balance(holder, "note"));
        assertEquals(BigInteger.ZERO, books.balance(issuer, "note"));
        books.checkInvariants();
    }

    @Test
    void namedPayeeIsRejectedForAnyoneElseAndStaysOutstanding() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(issuer, "note", BigInteger.valueOf(20));
        TxResult issued = books.issueCheck(issuer, "note", BigInteger.valueOf(20), "Steve", holder, true);
        PresentedCheck paper = new PresentedCheck(issued.instrumentId(), "note", BigInteger.valueOf(20), issuer, holder);

        assertEquals("wrong_payee", books.redeemCheck(stranger, paper).code());
        assertTrue(books.redeemCheck(holder, paper).success());
        assertEquals(BigInteger.ZERO, books.balance(holder, "note"));
        assertTrue(books.balance(holder, "diamond").add(books.balance(holder, "copper")).signum() > 0);
        books.checkInvariants();
    }

    @Test
    void twoIssuesNeverShareAnId() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(issuer, "note", BigInteger.valueOf(10));
        UUID first = books.issueCheck(issuer, "note", BigInteger.ONE, "Steve", null, true).instrumentId();
        UUID second = books.issueCheck(issuer, "note", BigInteger.ONE, "Steve", null, true).instrumentId();
        assertNotEquals(first, second);
    }

    @Test
    void unknownPaperIsRejected() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        PresentedCheck fake = new PresentedCheck(UUID.randomUUID(), "note", BigInteger.ONE, issuer, null);
        assertEquals("invalid_check", books.redeemCheck(holder, fake).code());
    }
}
