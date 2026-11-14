package dev.jdguo.bank.market;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.CompactFormat;
import dev.jdguo.bank.net.BankUiSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegimeSwitchTest {
    private final UUID issuer = UUID.fromString("12121212-1212-1212-1212-121212121212");
    private final UUID buyer = UUID.fromString("34343434-3434-3434-3434-343434343434");

    @Test
    void pegSwitchKeepsTheLockAndFloatCanOpenWhenReservesAreAlreadyThere() {
        BankBooks books = ready(new BigInteger("100"), new BigInteger("10"));
        PersonalNote note = books.personalNote(issuer);
        BigInteger locked = note.locked();
        BigInteger reserve = books.personalReserve(issuer, "diamond");

        RegimePreview early = books.switchPersonalRegime(issuer, NoteRegime.PEG, "diamond");
        assertFalse(early.allowed());
        assertTrue(early.reason().contains("联系汇率制"));
        assertTrue(early.reason().contains("尚缺"));
        assertTrue(early.reason().contains("央行准备金"));
        assertEquals(NoteRegime.FLOAT, note.regime());

        books.creditUnbacked(issuer, "note", new BigInteger("10"));
        assertTrue(books.depositCentralReserve(issuer, new BigInteger("10")).success());
        RegimePreview posted = books.switchPersonalRegime(issuer, NoteRegime.PEG, "diamond");

        assertTrue(posted.applied(), posted.reason());
        assertTrue(posted.reason().contains("联系汇率制"));
        assertEquals(NoteRegime.PEG, note.regime());
        assertEquals("diamond", note.anchor());
        assertEquals(locked, note.locked());
        assertEquals(new BigInteger("100"), note.circulation());
        assertEquals(reserve, books.personalReserve(issuer, "diamond"));
        assertEquals(0, BigDecimal.ONE.compareTo(note.openingDiamonds()));
        assertEquals(0, BigDecimal.ONE.compareTo(books.personalDiamondPrice(note)));
    }

    @Test
    void hardSwitchIsRefusedWhenTheLockCannotRedeemFace() {
        BankBooks books = ready(new BigInteger("100"), new BigInteger("20"));
        PersonalNote note = books.personalNote(issuer);
        note.setLocked(new BigInteger("40"));

        RegimePreview refused = books.switchPersonalRegime(issuer, NoteRegime.HARD, "diamond");

        assertFalse(refused.allowed());
        assertFalse(refused.applied());
        assertEquals(NoteRegime.FLOAT, note.regime());
        assertEquals(new BigInteger("40"), note.locked());
        assertTrue(refused.reason().contains("固定汇率制"));
        assertTrue(refused.reason().contains("面额"));
        assertTrue(refused.reason().contains("尚缺"));
    }

    @Test
    void hardSwitchIsRefusedWhenTheLiveQuoteHasLeftFace() {
        BankBooks books = ready(new BigInteger("100"), new BigInteger("20"));
        books.creditUnbacked(buyer, "note", new BigInteger("500"));
        books.deposit(buyer, "copper", new BigInteger("5000"));
        var bought = books.buyPersonalNote(buyer, "泉", new BigInteger("20"));
        assertTrue(bought.success(), bought.code());
        PersonalNote note = books.personalNote(issuer);
        assertTrue(books.personalDiamondPrice(note).compareTo(BigDecimal.ONE) > 0);

        RegimePreview refused = books.switchPersonalRegime(issuer, NoteRegime.HARD, null);

        assertFalse(refused.allowed(), refused.reason());
        assertEquals(NoteRegime.FLOAT, note.regime());
        assertTrue(refused.reason().contains("现价"));
        assertTrue(refused.reason().contains("面额"));
        assertEquals(new BigInteger("100"), note.locked());
    }

    @Test
    void snapshotListsSwitchReasonsAndPrintsIntegerZero() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertTrue(books.createPersonalNote(issuer, "泉", false, BigInteger.ONE).success());
        String json = BankUiSnapshot.json(books, issuer, true, "issuance", false);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject own = root.getAsJsonObject("ownNote");

        assertEquals("浮动汇率制", own.get("regimeName").getAsString());
        assertEquals("0", own.get("circulation").getAsString());
        assertEquals("0", own.get("locked").getAsString());
        assertEquals("0", own.get("centralReserve").getAsString());
        assertFalse(own.get("circulation").getAsString().contains("."));
        assertEquals(CompactFormat.format(books.supply("copper").reserve()), root.get("copperReserve").getAsString());
        assertEquals(CompactFormat.format(new BigInteger("1000")), root.get("diamondReserve").getAsString());
        assertEquals(CompactFormat.format(books.supply("note").reserve()), root.get("noteReserve").getAsString());
        assertEquals("2%", root.get("feePercent").getAsString());
        assertFalse(root.get("frozen").getAsBoolean());

        boolean sawHard = false;
        for (var element : own.getAsJsonArray("switches")) {
            JsonObject choice = element.getAsJsonObject();
            assertTrue(choice.has("allowed"));
            assertTrue(choice.get("name").getAsString().equals("浮动汇率制")
                    || choice.get("name").getAsString().equals("联系汇率制")
                    || choice.get("name").getAsString().equals("固定汇率制"));
            if ("HARD".equals(choice.get("regime").getAsString())) {
                sawHard = true;
            }
            if (!choice.get("allowed").getAsBoolean()) {
                assertTrue(choice.get("reason").getAsString().length() > 8);
            }
        }
        assertTrue(sawHard);
    }

    private BankBooks ready(BigInteger circulation, BigInteger central) {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertTrue(books.createPersonalNote(issuer, "泉", false, BigInteger.ONE).success());
        books.setPersonalReserve(issuer, "diamond", circulation);
        books.creditUnbacked(issuer, "note", central);
        assertTrue(books.depositCentralReserve(issuer, central).success());
        assertTrue(books.issuePersonalNote(issuer, circulation).success());
        return books;
    }
}
