package dev.jdguo.bank.instruments;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.market.PersonalNote;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotePaperTest {
    private final UUID player = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final UUID other = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void sameFaceCentralPaperSharesOneIdentityAndStacksAsAPile() {
        PresentedNote one = new PresentedNote("note", null, BigInteger.ONE);
        PresentedNote again = new PresentedNote("note", null, BigInteger.ONE);
        assertEquals(NoteSeries.key(one), NoteSeries.key(again));
        assertEquals(NotePaper.signature(one, "银行券"), NotePaper.signature(again, "银行券"));
        assertEquals(NoteSeries.identity("note", null, BigInteger.ONE, "银行券"),
                NoteSeries.identity("note", null, BigInteger.ONE, "银行券"));

        NoteSeries.PaperSlip pile = NoteSeries.plan(BigInteger.valueOf(64));
        assertEquals(BigInteger.ONE, pile.face());
        assertEquals(BigInteger.valueOf(64), pile.sheets());
        NoteSeries.PaperSlip hundred = NoteSeries.plan(BigInteger.valueOf(100));
        assertEquals(BigInteger.ONE, hundred.face());
        assertEquals(BigInteger.valueOf(100), hundred.sheets());
    }

    @Test
    void personalPaperDoesNotStackWithCentralOrAnotherFace() {
        UUID noteId = UUID.randomUUID();
        PresentedNote central = new PresentedNote("note", null, BigInteger.ONE);
        PresentedNote personal = new PresentedNote(null, noteId, BigInteger.ONE);
        PresentedNote faceTen = new PresentedNote("note", null, BigInteger.TEN);
        assertNotEquals(NoteSeries.key(central), NoteSeries.key(personal));
        assertNotEquals(NoteSeries.key(central), NoteSeries.key(faceTen));
        assertNotEquals(NotePaper.signature(central, "银行券"), NotePaper.signature(personal, "Ada"));
        assertFalse(NotePaper.signature(central, "银行券").equals(NotePaper.signature(faceTen, "银行券")));
    }

    @Test
    void issueDepositCopyAndAlreadyUsedCentralPaper() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(player, "note", BigInteger.valueOf(500));
        BigInteger liability = books.supply("note").liability();

        PaperTake issued = books.issueCentralPaper(player, BigInteger.valueOf(64));

        assertTrue(issued.result().success());
        assertEquals(BigInteger.ONE, issued.face());
        assertEquals(BigInteger.valueOf(64), issued.sheets());
        assertEquals(BigInteger.valueOf(436), books.balance(player, "note"));
        assertEquals(liability, books.supply("note").liability());
        assertEquals(BigInteger.valueOf(64), books.paperOutstanding("note", null, BigInteger.ONE));

        PresentedNote paper = new PresentedNote("note", null, BigInteger.ONE);
        TxResult firstHalf = books.redeemPaper(other, paper, BigInteger.valueOf(32));
        TxResult secondHalf = books.redeemPaper(other, paper, BigInteger.valueOf(32));
        assertTrue(firstHalf.success());
        assertTrue(secondHalf.success());
        assertEquals("ok", secondHalf.code());
        assertEquals(BigInteger.valueOf(64), books.balance(other, "note"));
        assertEquals(BigInteger.ZERO, books.paperOutstanding("note", null, BigInteger.ONE));

        TxResult copy = books.redeemPaper(player, paper, BigInteger.valueOf(64));
        TxResult alreadyUsed = books.redeemPaper(other, paper, BigInteger.ONE);
        assertFalse(copy.success());
        assertEquals("note_spent", copy.code());
        assertEquals("note_spent", alreadyUsed.code());
        assertEquals(BigInteger.valueOf(436), books.balance(player, "note"));
        assertEquals(BigInteger.valueOf(64), books.balance(other, "note"));
        books.checkInvariants();
    }

    @Test
    void extraCopiesAreNotCreditedAndAReloadStillRejectsThem() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(player, "note", BigInteger.TEN);
        assertTrue(books.issueCentralPaper(player, BigInteger.valueOf(4)).result().success());
        PresentedNote paper = new PresentedNote("note", null, BigInteger.ONE);

        TxResult mixed = books.redeemPaper(player, paper, BigInteger.valueOf(6));

        assertTrue(mixed.success());
        assertEquals("trimmed", mixed.code());
        assertEquals(BigInteger.valueOf(4), mixed.amount());
        assertEquals(BigInteger.TEN, books.balance(player, "note"));
        assertEquals(BigInteger.ZERO, books.paperOutstanding("note", null, BigInteger.ONE));

        BankBooks loaded = BankBooks.fromJson(books.toJson(), BankConfig.defaults());
        TxResult afterReload = loaded.redeemPaper(player, paper, BigInteger.ONE);
        assertEquals("note_spent", afterReload.code());
        assertEquals(BigInteger.TEN, loaded.balance(player, "note"));
        loaded.checkInvariants();
    }

    @Test
    void mergingTwoWithdrawalsDoesNotMintAThird() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(player, "note", BigInteger.valueOf(30));
        assertTrue(books.issueCentralPaper(player, BigInteger.TEN).result().success());
        assertTrue(books.issueCentralPaper(player, BigInteger.TEN).result().success());
        assertEquals(BigInteger.valueOf(20), books.paperOutstanding("note", null, BigInteger.ONE));

        PresentedNote paper = new PresentedNote("note", null, BigInteger.ONE);
        TxResult merged = books.redeemPaper(other, paper, BigInteger.valueOf(20));
        TxResult extra = books.redeemPaper(other, paper, BigInteger.ONE);

        assertTrue(merged.success());
        assertEquals(BigInteger.valueOf(20), merged.amount());
        assertEquals("note_spent", extra.code());
        assertEquals(BigInteger.valueOf(20), books.balance(other, "note"));
        books.checkInvariants();
    }

    @Test
    void hugeAmountIsOneRepeatableFaceAndStillPaysOnce() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        BigInteger huge = BankBooks.MAX_PHYSICAL.add(BigInteger.ONE);
        books.creditUnbacked(player, "note", huge.multiply(BigInteger.TWO));
        PaperTake first = books.issueCentralPaper(player, huge);
        PaperTake second = books.issueCentralPaper(player, huge);

        assertEquals(huge, first.face());
        assertEquals(BigInteger.ONE, first.sheets());
        assertEquals(first.face(), second.face());
        assertEquals(BigInteger.TWO, books.paperOutstanding("note", null, huge));
        assertNotEquals(NoteSeries.key("note", null, BigInteger.ONE), NoteSeries.key("note", null, huge));

        PresentedNote paper = new PresentedNote("note", null, huge);
        TxResult paid = books.redeemPaper(other, paper, BigInteger.TWO);
        TxResult copy = books.redeemPaper(other, paper, BigInteger.ONE);
        assertTrue(paid.success());
        assertEquals(huge.multiply(BigInteger.TWO), paid.amount());
        assertEquals("note_spent", copy.code());
        books.checkInvariants();
    }

    @Test
    void personalPaperIsIssuedDepositedAndACopyIsRefused() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setPersonalReserve(player, "diamond", BigInteger.valueOf(100));
        assertTrue(books.createPersonalNote(player, "Ada", false, BigInteger.ONE).success());
        PersonalNote note = books.personalNote(player);
        assertTrue(books.issuePersonalNote(player, BigInteger.valueOf(20)).success());

        PaperTake taken = books.issuePersonalPaper(player, note.id(), BigInteger.TEN);
        assertTrue(taken.result().success());
        assertEquals(BigInteger.ONE, taken.face());
        assertEquals(BigInteger.TEN, books.personalHolding(note.id(), player));
        assertEquals(BigInteger.TEN, books.paperOutstanding(null, note.id(), BigInteger.ONE));
        assertEquals(BigInteger.ZERO, books.paperOutstanding("note", null, BigInteger.ONE));

        PresentedNote personal = new PresentedNote(null, note.id(), BigInteger.ONE);
        PresentedNote central = new PresentedNote("note", null, BigInteger.ONE);
        TxResult deposited = books.redeemPaper(other, personal, BigInteger.TEN);
        TxResult copy = books.redeemPaper(other, personal, BigInteger.TEN);
        TxResult centralCopy = books.redeemPaper(other, central, BigInteger.TEN);

        assertTrue(deposited.success());
        assertEquals(BigInteger.TEN, books.personalHolding(note.id(), other));
        assertEquals(BigInteger.TEN, books.personalHolding(note.id(), player));
        assertEquals("note_spent", copy.code());
        assertEquals(BigInteger.TEN, books.personalHolding(note.id(), other));
        assertEquals("note_spent", centralCopy.code());
        assertEquals(BigInteger.ZERO, books.balance(other, "note"));
        assertEquals(BigInteger.valueOf(20), note.circulation());

        BankBooks loaded = BankBooks.fromJson(books.toJson(), BankConfig.defaults());
        assertEquals("note_spent", loaded.redeemPaper(other, personal, BigInteger.ONE).code());
        assertEquals(BigInteger.TEN, loaded.personalHolding(note.id(), other));
        loaded.checkInvariants();
    }

    @Test
    void tamperedCurrencyIsNotPaid() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(player, "note", BigInteger.TEN);
        books.issueCentralPaper(player, BigInteger.TEN);
        PresentedNote forged = new PresentedNote("diamond", null, BigInteger.ONE);

        TxResult result = books.redeemPaper(player, forged, BigInteger.TEN);

        assertEquals("note_tampered", result.code());
        assertEquals(BigInteger.ZERO, books.balance(player, "diamond"));
        assertEquals(BigInteger.TEN, books.paperOutstanding("note", null, BigInteger.ONE));
        books.checkInvariants();
    }
}
