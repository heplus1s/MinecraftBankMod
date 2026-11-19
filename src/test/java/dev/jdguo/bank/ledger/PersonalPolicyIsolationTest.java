package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.NoteRegime;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PersonalPolicyIsolationTest {
    @Test void savingBandCannotChangeAnotherModeAndDoesNotMoveFunds() {
        var books = BankBooks.create(BankConfig.defaults());
        UUID issuer = UUID.randomUUID();
        assertTrue(books.createPersonalNote(issuer, "区", NoteRegime.FLOAT, "diamond", BigInteger.ONE).success());
        var note = books.personalNote(issuer);
        assertEquals("inactive_regime", books.setPersonalPolicyBand(issuer, n("1"), n("2")).code());
        assertEquals("inactive_regime", books.setPersonalPolicy(issuer, NoteRegime.HARD, n("2"), n("2"), n("2")).code());
        assertEquals("stale_regime", books.switchPersonalPolicyRegime(issuer, NoteRegime.PEG, NoteRegime.HARD).code());
        assertEquals(NoteRegime.FLOAT, note.regime());
        assertTrue(books.switchPersonalPolicyRegime(issuer, NoteRegime.FLOAT, NoteRegime.PEG).success());
        assertFalse(books.personalAutomaticPolicyActive(note));
        assertFalse(books.setPersonalPolicyBand(issuer, n("2"), n("1")).success());
        assertTrue(books.setPersonalPolicyBand(issuer, n("1.0001"), n("1.0002")).success());
        assertEquals(n("1.0002"), books.personalPolicy(note.id()).target());
        assertEquals(NoteRegime.PEG, note.regime());
        assertEquals(BigInteger.ZERO, note.circulation());
        assertEquals(Money.ZERO, books.effectiveBackingExact(note));
        assertTrue(books.personalAutomaticPolicyActive(note));
    }

    @Test void oldActivePolicyPersistsPerModeAcrossSwitchAndReload() {
        var books = BankBooks.create(BankConfig.defaults());
        UUID issuer = UUID.randomUUID();
        assertTrue(books.createPersonalNote(issuer, "换", NoteRegime.HARD, "copper", BigInteger.ONE).success());
        var note = books.personalNote(issuer);
        assertTrue(books.setPersonalPolicy(issuer, NoteRegime.HARD, n("2"), n("2"), n("2")).success());
        assertTrue(books.switchPersonalPolicyRegime(issuer, NoteRegime.HARD, NoteRegime.PEG).success());
        assertFalse(books.personalAutomaticPolicyActive(note));
        assertTrue(books.setPersonalPolicyBand(issuer, n("3"), n("5")).success());
        books = BankBooks.fromJson(books.toJson(), BankConfig.defaults());
        assertTrue(books.switchPersonalPolicyRegime(issuer, NoteRegime.PEG, NoteRegime.HARD).success());
        note = books.personalNote(issuer);
        assertEquals("copper", note.anchor());
        assertEquals(n("2"), books.personalPolicy(note.id()).target());
        assertTrue(books.switchPersonalPolicyRegime(issuer, NoteRegime.HARD, NoteRegime.PEG).success());
        assertEquals(n("3"), books.personalPolicy(note.id()).lower());
        assertEquals(n("5"), books.personalPolicy(note.id()).upper());
        books.checkInvariants();
    }

    private static BigDecimal n(String value) { return new BigDecimal(value); }
}
