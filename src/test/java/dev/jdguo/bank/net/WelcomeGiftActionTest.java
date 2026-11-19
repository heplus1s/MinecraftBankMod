package dev.jdguo.bank.net;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WelcomeGiftActionTest {
    @Test void parsesExactClaimAndSettings() {
        assertTrue(WelcomeGiftAction.parse(new String[]{"welcome-claim", "12"}).claim());
        var set = WelcomeGiftAction.parse(new String[]{"admin-welcome-set", "12", "false", "100.1250", "personal:abc"});
        assertFalse(set.claim()); assertFalse(set.enabled()); assertEquals("100.1250", set.amount().toPlainString());
        assertEquals("personal:abc", set.currency());
    }
    @Test void rejectsMalformedAndExtraArguments() {
        for (String command : new String[]{"welcome-claim -1", "welcome-claim 9223372036854775808", "welcome-claim 1 junk",
                "admin-welcome-set 1 yes 100 note", "admin-welcome-set 1 true 1e3 note", "admin-welcome-set 1 true 0 note",
                "admin-welcome-set 1 true 1.00001 note", "admin-welcome-set 1 false NaN note", "admin-welcome-set 1 true 100 note junk"})
            assertThrows(IllegalArgumentException.class, () -> WelcomeGiftAction.parse(command.split(" ")), command);
    }
    @Test void managementCannotBeForgedInPlayerSession() {
        assertFalse(BankUiAccess.actionAllowed("admin-welcome-set", false, true));
        assertFalse(BankUiAccess.actionAllowed("admin-welcome-set", true, false));
        assertTrue(BankUiAccess.actionAllowed("admin-welcome-set", true, true));
        assertTrue(BankUiAccess.actionAllowed("welcome-claim", false, false));
    }
}
