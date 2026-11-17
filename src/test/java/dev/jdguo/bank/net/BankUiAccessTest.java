package dev.jdguo.bank.net;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankUiAccessTest {
    @Test void activeAdminSessionIsRevokedWithoutAnyLedgerChange() {
        assertTrue(BankUiAccess.sessionAllowed(true, true));
        assertFalse(BankUiAccess.sessionAllowed(true, false));
        assertTrue(BankUiAccess.sessionAllowed(false, false));
        assertTrue(BankUiAccess.sessionAllowed(false, true));
    }
    @Test void remoteAdminKeepsManagementDuringUpgrade() {
        assertTrue(BankUiAccess.management(true, false, true));
    }
    @Test void kioskManagementTabAlsoKeepsManagement() {
        assertTrue(BankUiAccess.management(false, true, true));
    }
    @Test void ordinaryKioskDoesNotBecomeAdminMerelyBecausePlayerIsOp() {
        assertFalse(BankUiAccess.management(false, false, true));
    }
    @Test void permissionRevocationNeverUpgradesToAdmin() {
        assertFalse(BankUiAccess.management(true, true, false));
        assertFalse(BankUiAccess.management(true, false, false));
        assertFalse(BankUiAccess.management(false, true, false));
    }
}
