package dev.jdguo.bank.market;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.CompactFormat;
import dev.jdguo.bank.net.BankUiSnapshot;
import dev.jdguo.bank.text.BankPhrases;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalNoteControlsTest {
    private final UUID issuer = UUID.fromString("12121212-1212-1212-1212-121212121212");
    private final UUID other = UUID.fromString("34343434-3434-3434-3434-343434343434");

    @Test
    void depositedDiamondsQuoteAFloatNoteEvenWhenNothingIsLocked() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertTrue(books.createPersonalNote(issuer, "屎币", NoteRegime.FLOAT, "diamond", BigInteger.ONE).success());
        assertTrue(books.issuePersonalNoteUnbacked(issuer, new BigInteger("2001")).success());
        assertTrue(books.deposit(issuer, "diamond", new BigInteger("300")).success());
        assertTrue(books.depositPersonalReserve(issuer, new BigInteger("300")).success());

        PersonalNote note = books.personalNote(issuer);
        assertEquals(BigInteger.ZERO, note.locked());
        assertEquals(new BigInteger("300"), books.effectiveBacking(note));
        assertTrue(books.personalDiamondPrice(note).signum() > 0);

        JsonObject own = JsonParser.parseString(BankUiSnapshot.json(books, issuer, false, "issuance", false))
                .getAsJsonObject().getAsJsonObject("ownNote");
        assertTrue(own.has("diamondPrice"));
        assertEquals("300", own.get("issuanceReserve").getAsString());
        assertEquals("0", own.get("locked").getAsString());
        assertFalse(own.get("redemptionPaused").getAsBoolean());
    }

    @Test
    void increaseCreditsOnlyTheIssuerAndDecreaseRefusesAShortHolding() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertTrue(books.createPersonalNote(issuer, "屎币", NoteRegime.FLOAT, "diamond", BigInteger.ONE).success());
        BigDecimal liability = books.supply("note").liabilityExact();
        PersonalNote note = books.personalNote(issuer);

        assertEquals("par", books.issuePersonalNoteUnbacked(issuer, BigInteger.ZERO).code());
        assertTrue(books.issuePersonalNoteUnbacked(issuer, new BigInteger("10")).success());

        assertEquals(new BigInteger("10"), note.circulation());
        assertEquals(new BigInteger("10"), books.personalHolding(note.id(), issuer));
        assertEquals(BigInteger.ZERO, books.personalHolding(note.id(), other));
        assertEquals(BigInteger.ZERO, note.locked());
        assertEquals(0, liability.compareTo(books.supply("note").liabilityExact()));

        assertTrue(books.deposit(other, "diamond", BigInteger.TEN).success());
        BigInteger otherDiamonds = books.balance(other, "diamond");
        NoteTrade refused = books.retirePersonalNote(issuer, new BigInteger("11"));
        assertEquals("funds", refused.code());
        assertTrue(BankPhrases.noteRejected(refused.code()).zh().contains("你的账户余额不足"));
        assertEquals(new BigInteger("10"), note.circulation());
        assertEquals(new BigInteger("10"), books.personalHolding(note.id(), issuer));
        assertEquals(otherDiamonds, books.balance(other, "diamond"));

        assertTrue(books.retirePersonalNote(issuer, new BigInteger("4")).success());
        assertEquals(new BigInteger("6"), note.circulation());
        assertEquals(new BigInteger("6"), books.personalHolding(note.id(), issuer));
        assertEquals(BigInteger.ZERO, books.personalHolding(note.id(), other));
        assertEquals(BigInteger.ZERO, note.locked());
        assertEquals(0, liability.compareTo(books.supply("note").liabilityExact()));
    }

    @Test
    void retirementReleasesALockThatNoLongerCoversTheRemainingCirculation() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertTrue(books.createPersonalNote(issuer, "屎币", NoteRegime.FLOAT, "diamond", BigInteger.ONE).success());
        assertTrue(books.deposit(issuer, "diamond", BigInteger.TEN).success());
        assertTrue(books.depositPersonalReserve(issuer, BigInteger.TEN).success());
        assertTrue(books.issuePersonalNote(issuer, BigInteger.TEN).success());
        assertEquals(BigInteger.TEN, books.personalNote(issuer).locked());

        assertTrue(books.retirePersonalNote(issuer, new BigInteger("4")).success());

        PersonalNote note = books.personalNote(issuer);
        assertEquals(new BigInteger("6"), note.locked());
        assertTrue(books.withdrawPersonalReserve(issuer, new BigInteger("4")).success());
        assertEquals(new BigInteger("6"), books.personalReserve(issuer, "diamond"));
        assertEquals(new BigInteger("4"), books.balance(issuer, "diamond"));
    }

    @Test
    void freezeBlocksRedemptionUntilTheIssuerResumesIt() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertTrue(books.createPersonalNote(issuer, "屎币", NoteRegime.FLOAT, "diamond", BigInteger.ONE).success());
        assertTrue(books.deposit(issuer, "diamond", BigInteger.TEN).success());
        assertTrue(books.depositPersonalReserve(issuer, BigInteger.TEN).success());
        assertTrue(books.issuePersonalNoteUnbacked(issuer, BigInteger.TEN).success());
        books.deposit(other, "copper", new BigInteger("20000"));
        books.creditHeld(other, "note", new BigInteger("100"));
        assertTrue(books.buyPersonalNote(other, "屎币", BigInteger.ONE).success());
        PersonalNote note = books.personalNote(issuer);
        BigInteger reserve = books.personalReserve(issuer, "diamond");
        BigInteger otherHolding = books.personalHolding(note.id(), other);

        assertTrue(books.setPersonalRedemptionPaused(issuer, true).success());
        Redemption blocked = books.redeemPersonal(other, "屎币", BigInteger.ONE, "diamond");
        assertEquals("paused", blocked.code());
        assertTrue(BankPhrases.noteRedemptionPaused(blocked.note()).zh().contains("已暂停兑付"));
        assertEquals(otherHolding, books.personalHolding(note.id(), other));
        assertEquals(reserve, books.personalReserve(issuer, "diamond"));
        assertEquals(BigInteger.TEN, note.circulation());

        assertTrue(books.setPersonalRedemptionPaused(issuer, false).success());
        var open = books.redeemPersonalToSavings(other, note.id(), BigDecimal.ONE, "unpaused-redeem");
        assertTrue(open.success(), open.code());
        assertTrue(books.balanceExact(other, "diamond").signum() > 0);
        assertEquals(0, books.personalActiveExact(note).compareTo(new BigDecimal("9")));
    }

    @Test
    void anOlderSaveKeepsIssuedHoldingsAndReadsAMissingPauseAsOpen() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertTrue(books.createPersonalNote(issuer, "屎币", NoteRegime.FLOAT, "diamond", BigInteger.ONE).success());
        assertTrue(books.issuePersonalNoteUnbacked(issuer, new BigInteger("2001")).success());
        assertTrue(books.deposit(issuer, "diamond", new BigInteger("300")).success());
        assertTrue(books.depositPersonalReserve(issuer, new BigInteger("300")).success());
        var root = JsonParser.parseString(books.toJson()).getAsJsonObject();
        root.getAsJsonArray("personalNotes").get(0).getAsJsonObject().remove("redemptionPaused");

        BankBooks loaded = BankBooks.fromJson(root.toString(), BankConfig.defaults());
        PersonalNote note = loaded.personalNote(issuer);
        assertEquals(new BigInteger("2001"), note.circulation());
        assertEquals(new BigInteger("2001"), loaded.personalHolding(note.id(), issuer));
        assertEquals(new BigInteger("300"), loaded.personalReserve(issuer, "diamond"));
        assertEquals(BigInteger.ZERO, note.locked());
        assertFalse(note.redemptionPaused());
        assertTrue(loaded.personalDiamondPrice(note).signum() > 0);
        JsonObject own = JsonParser.parseString(BankUiSnapshot.json(loaded, issuer, false, "issuance", false))
                .getAsJsonObject().getAsJsonObject("ownNote");
        assertEquals(CompactFormat.format(new BigInteger("2001")), own.get("holding").getAsString());
        assertEquals(CompactFormat.format(new BigInteger("2001")), own.get("circulation").getAsString());
    }
}
