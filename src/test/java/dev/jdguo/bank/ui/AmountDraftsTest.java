package dev.jdguo.bank.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmountDraftsTest {
    @Test void allDoesNotLeakFromDepositToOtherOperations() {
        AmountDrafts drafts = new AmountDrafts();
        drafts.select("DEPOSIT");
        drafts.remember("全部");
        for (String page : new String[]{"WITHDRAW", "ISSUE", "MARKET", "ISSUANCE", "ISSUANCE_RESERVE", "EXCHANGE"}) {
            assertEquals("1", drafts.select(page));
        }
        assertEquals("全部", drafts.select("DEPOSIT"));
    }
    @Test void editsIncludingEmptySurviveSamePageRebuilds() {
        AmountDrafts drafts = new AmountDrafts();
        drafts.select("DEPOSIT");
        drafts.remember("全部");
        drafts.remember("");
        assertEquals("", drafts.select("DEPOSIT"));
        drafts.remember("12");
        assertEquals("12", drafts.select("DEPOSIT"));
        assertEquals("12", QuantityTokens.token(drafts.select("DEPOSIT")));
    }
    @Test void eachOperationRetainsItsOwnDraftWhenReturning() {
        AmountDrafts drafts = new AmountDrafts();
        drafts.select("DEPOSIT"); drafts.remember("全部");
        drafts.select("WITHDRAW"); drafts.remember("3");
        drafts.select("ISSUE"); drafts.remember("12.5");
        assertEquals("全部", drafts.select("DEPOSIT"));
        assertEquals("3", drafts.select("WITHDRAW"));
        assertEquals("12.5", drafts.select("ISSUE"));
    }
    @Test void invalidPhysicalSeedCannotLockIntegerField() {
        for (String text : new String[]{"全部", "all", "max", "12.5", "1234567890"}) {
            assertEquals("1", AmountDrafts.physicalInput(text));
        }
        assertEquals("", AmountDrafts.physicalInput(""));
        assertEquals("123", AmountDrafts.physicalInput("123"));
        assertTrue(AmountDrafts.isPhysicalInput(""));
        assertTrue(AmountDrafts.isPhysicalInput("12"));
        assertFalse(AmountDrafts.isPhysicalInput("全部1"));
    }
    @Test void separateScreenInstancesDoNotShareDrafts() {
        AmountDrafts a = new AmountDrafts();
        a.select("DEPOSIT"); a.remember("全部");
        assertEquals("1", new AmountDrafts().select("DEPOSIT"));
    }
}
