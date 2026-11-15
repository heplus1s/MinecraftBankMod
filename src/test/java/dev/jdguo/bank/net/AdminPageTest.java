package dev.jdguo.bank.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminPageTest {
    @Test
    void bankAdminOpensTheManagementPageAndAKioskDoesNot() {
        BankUiNetwork.Session admin = new BankUiNetwork.Session("管理", true);
        BankUiNetwork.Session kiosk = new BankUiNetwork.Session("柜台", false);

        assertTrue(admin.admin);
        assertEquals("management", admin.page);
        assertFalse(kiosk.admin);
        assertEquals("savings", kiosk.page);
    }
}
