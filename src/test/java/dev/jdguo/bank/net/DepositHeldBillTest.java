package dev.jdguo.bank.net;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.instruments.PresentedCheck;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.TxResult;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DepositHeldBillTest {
    private final UUID issuer = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddddd1");
    private final UUID holder = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddddd2");

    @Test
    void depositHeldRedeemsOnlyTheBillInHand() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(issuer, "note", new BigInteger("400"));
        TxResult first = books.issueCheck(issuer, "note", new BigInteger("100"), "Ada", null, true);
        TxResult second = books.issueCheck(issuer, "note", new BigInteger("100"), "Ada", null, true);
        PresentedCheck hand = presented(books, first);
        PresentedCheck otherBill = presented(books, second);

        BankUiService.MainHandBill paid = BankUiService.redeemMainHandBill(books, holder, hand);

        assertTrue(paid.saveLedger());
        assertTrue(paid.clearHand());
        assertTrue(paid.spoken().zh().startsWith("[银行] "));
        assertTrue(paid.spoken().zh().contains("兑付"));
        assertEquals("OUTSTANDING", books.check(second.instrumentId()).status().name());
        assertTrue(books.redeemCheck(holder, otherBill).success());

        BigInteger diamonds = books.balance(holder, "diamond");
        BigInteger copper = books.balance(holder, "copper");
        BankUiService.MainHandBill notABill = BankUiService.redeemMainHandBill(books, holder, null);
        assertFalse(notABill.clearHand());
        assertFalse(notABill.saveLedger());
        assertEquals("[银行] 请把一张汇票拿在手上。", notABill.spoken().zh());
        assertEquals(diamonds, books.balance(holder, "diamond"));
        assertEquals(copper, books.balance(holder, "copper"));
    }

    @Test
    void aSecondPresentationOfTheSameBillIsRefusedAndCleared() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(issuer, "note", new BigInteger("80"));
        TxResult issued = books.issueCheck(issuer, "note", new BigInteger("40"), "Ada", null, true);
        PresentedCheck hand = presented(books, issued);
        PresentedCheck spare = presented(books, books.issueCheck(issuer, "note", new BigInteger("10"), "Ada", null, true));

        assertTrue(BankUiService.redeemMainHandBill(books, holder, hand).saveLedger());
        BankUiService.MainHandBill again = BankUiService.redeemMainHandBill(books, holder, hand);

        assertFalse(again.saveLedger());
        assertTrue(again.clearHand());
        assertEquals("[银行] 这张汇票已经兑付过。", again.spoken().zh());
        assertEquals("OUTSTANDING", books.check(spare.id()).status().name());
    }

    private static PresentedCheck presented(BankBooks books, TxResult issued) {
        var record = books.check(issued.instrumentId());
        return new PresentedCheck(record.id(), record.currency(), record.amount(), record.issuer(), record.payee());
    }
}
