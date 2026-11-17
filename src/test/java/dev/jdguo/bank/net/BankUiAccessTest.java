package dev.jdguo.bank.net;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankUiAccessTest {
    @Test void forgedManagementActionsNeedBothSessionAndCurrentPermission() {
        String[] actions = {"admin-rename", "admin-adjust-confirm", "admin-funding-confirm",
                "admin-treasury-withdraw", "admin-freeze", "admin-unbacked", "admin-retire",
                "admin-backed", "admin-reserve-from-deposits", "admin-future-operation"};
        for (String action : actions) {
            assertFalse(BankUiAccess.actionAllowed(action, false, false), action);
            assertFalse(BankUiAccess.actionAllowed(action, true, false), action);
            assertFalse(BankUiAccess.actionAllowed(action, false, true), action);
            assertTrue(BankUiAccess.actionAllowed(action, true, true), action);
        }
    }
    @Test void ordinaryActionsRemainAvailableWithoutOperatorPermission() {
        assertTrue(BankUiAccess.actionAllowed("withdraw", false, false));
        assertTrue(BankUiAccess.actionAllowed("note-create", false, false));
        assertFalse(BankUiAccess.actionAllowed(null, true, true));
    }

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
