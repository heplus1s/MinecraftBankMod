package dev.jdguo.bank.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankPolicyTest {
    @Test void independentFormsUseExplicitNonUnitRatios() {
        assertEquals("note-policy peg 0.25 0.2 0.3", BankPolicy.command("peg", "0.25", "0.2", "0.3"));
        assertEquals("note-policy hard 2 2 2", BankPolicy.command("hard", "2", null, null));
        assertEquals("note-policy float 0.125 0.125 0.125", BankPolicy.command("float", "0.125", null, null));
    }
    @Test void invalidInputsCannotBecomeCommands() {
        assertThrows(IllegalArgumentException.class, () -> BankPolicy.command("peg", "1", "2", "3"));
        for (String value : new String[] {"0", "-1", "NaN", "1e99", "1 2", "1.123456789"}) {
            assertThrows(IllegalArgumentException.class, () -> BankPolicy.command("hard", value, null, null));
        }
    }
}
