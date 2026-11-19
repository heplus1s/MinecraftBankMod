package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.NoteRegime;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PersonalQuantityPlanTest {
    @Test void floatPreviewIsReadOnlyAndConfirmationCannotBeReplayedOrTransferred() {
        var books = BankBooks.create(BankConfig.defaults());
        UUID owner = create(books, NoteRegime.FLOAT);
        var id = books.personalNote(owner).id();
        String before = books.toJson();
        var plan = PersonalQuantityPlan.preview(books, owner, "increase", "0004");
        assertTrue(plan.allowed(), plan.reason());
        assertEquals(before, books.toJson());
        assertEquals("4", plan.json().get("holdingAfter").getAsString());
        assertEquals("4", plan.json().get("activeAfter").getAsString());
        assertEquals("0", plan.json().get("reserveAfter").getAsString());
        assertFalse(plan.confirm(books, UUID.randomUUID()).success());
        assertEquals(before, books.toJson());
        assertTrue(plan.confirm(books, owner).success());
        assertEquals(0, new BigDecimal("4").compareTo(books.personalHoldingExact(id, owner)));
        assertEquals("stale_plan", plan.confirm(books, owner).code());
        books.checkInvariants();
    }

    @Test void peggedAndFixedIssuanceDoNotBypassReserves() {
        for (NoteRegime regime : new NoteRegime[]{NoteRegime.PEG, NoteRegime.HARD}) {
            var books = BankBooks.create(BankConfig.defaults());
            UUID owner = create(books, regime);
            assertTrue(books.setPersonalPolicy(owner, regime, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE).success());
            String before = books.toJson();
            assertFalse(PersonalQuantityPlan.preview(books, owner, "increase", "5").allowed());
            assertFalse(PersonalQuantityPlan.preview(books, owner, "increase", "1.5").allowed());
            assertEquals(before, books.toJson());
            books.deposit(owner, "diamond", BigInteger.valueOf(6));
            assertTrue(books.depositPersonalReserveExact(owner, new BigDecimal("6")).success());
            var plan = PersonalQuantityPlan.preview(books, owner, "increase", "5");
            assertTrue(plan.allowed(), plan.reason());
            assertEquals("5", plan.json().get("lockedAfter").getAsString());
            assertEquals("6", plan.json().get("reserveAfter").getAsString());
            assertTrue(plan.confirm(books, owner).success());
            books.checkInvariants();
        }
    }

    @Test void retirementRefusesStaleBalancesAndLeavesOtherHoldersUntouched() {
        var books = BankBooks.create(BankConfig.defaults());
        UUID owner = create(books, NoteRegime.FLOAT), publicHolder = UUID.randomUUID();
        UUID id = books.personalNote(owner).id();
        assertTrue(books.issuePersonalNoteUnbacked(owner, BigInteger.TEN).success());
        var plan = PersonalQuantityPlan.preview(books, owner, "decrease", "9");
        assertTrue(plan.allowed(), plan.reason());
        assertTrue(books.transferPersonal(owner, publicHolder, "量", BigInteger.valueOf(3)).success());
        String before = books.toJson();
        assertEquals("stale_plan", plan.confirm(books, owner).code());
        assertEquals(before, books.toJson());
        assertFalse(PersonalQuantityPlan.preview(books, owner, "decrease", "8").allowed());
        var refreshed = PersonalQuantityPlan.preview(books, owner, "decrease", "7");
        assertTrue(refreshed.confirm(books, owner).success());
        assertEquals(0, new BigDecimal("3").compareTo(books.personalHoldingExact(id, publicHolder)));
        assertEquals(BigInteger.valueOf(3), books.personalNote(owner).circulation());
        books.checkInvariants();
    }

    private static UUID create(BankBooks books, NoteRegime regime) {
        UUID owner = UUID.randomUUID();
        assertTrue(books.createPersonalNote(owner, "量", regime, "diamond", BigInteger.ONE).success());
        return owner;
    }
}
