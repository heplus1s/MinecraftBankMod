package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CentralIssuePlanTest {
    private BankBooks fixture() {
        BankBooks b = BankBooks.create(BankConfig.defaults());
        b.setAlpha("note", BigDecimal.ZERO);
        b.setAlpha("copper", BigDecimal.ZERO);
        b.setAlpha("diamond", BigDecimal.ZERO);
        b.supply("copper").setReserve(BigInteger.ZERO);
        b.supply("diamond").setReserve(BigInteger.valueOf(1000));
        b.supply("note").setLiability(BigInteger.valueOf(100));
        return b;
    }
    @Test void editedTargetCannotConfirmOldQuote() {
        assertTrue(CentralIssuePlan.sameTarget("5.0000", "5"));
        assertFalse(CentralIssuePlan.sameTarget("4", "5"));
        assertFalse(CentralIssuePlan.sameTarget("", "5"));
        assertFalse(CentralIssuePlan.sameTarget("5", null));
    }
    @Test void concurrentFreezeChangeDoesNotToggleOppositeDirection() {
        BankBooks b = fixture();
        b.setFrozen(true);
        assertFalse(b.setFrozenIfMatches(false, true).success());
        assertTrue(b.frozen());
        assertTrue(b.setFrozenIfMatches(true, false).success());
        assertFalse(b.frozen());
    }
    @Test void targetIsNotOneToOneAndPreviewDoesNotPost() {
        BankBooks b = fixture();
        String before = b.toJson();
        CentralIssuePlan p = b.previewCentralIssue(new BigDecimal("5"));
        assertTrue(p.allowed(), p.reason());
        assertEquals(BigInteger.valueOf(100), p.amount());
        assertEquals(0, p.resultingRate().compareTo(new BigDecimal("5")));
        assertEquals(before, b.toJson());
        BigDecimal custody = b.supply("diamond").depositVaultExact();
        assertTrue(b.confirmCentralIssue(UUID.randomUUID(), p).success());
        assertEquals(custody, b.supply("diamond").depositVaultExact());
        assertFalse(b.confirmCentralIssue(UUID.randomUUID(), p).success());
    }
    @Test void changedStateRequiresNewPreview() {
        BankBooks b = fixture();
        CentralIssuePlan p = b.previewCentralIssue(new BigDecimal("5"));
        b.deposit(UUID.randomUUID(), "diamond", BigInteger.ONE);
        BigDecimal before = b.supply("note").liabilityExact();
        assertFalse(b.confirmCentralIssue(UUID.randomUUID(), p).success());
        assertEquals(before, b.supply("note").liabilityExact());
    }
    @Test void invalidOrUnfundedTargetsNeverPost() {
        BankBooks b = fixture();
        String before = b.toJson();
        assertFalse(b.previewCentralIssue(BigDecimal.ZERO).allowed());
        assertFalse(b.previewCentralIssue(new BigDecimal("100")).allowed());
        assertFalse(b.previewCentralIssue(new BigDecimal("0.0001")).allowed());
        assertEquals(before, b.toJson());
    }
    @Test void previewSurvivesCopyOnWriteSerialization() {
        BankBooks b = fixture();
        CentralIssuePlan p = b.previewCentralIssue(new BigDecimal("5"));
        BankBooks copy = BankBooks.fromJson(b.toJson(), BankConfig.defaults());
        assertTrue(copy.confirmCentralIssue(UUID.randomUUID(), p).success());
    }
}
