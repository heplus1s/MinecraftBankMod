package dev.jdguo.bank.ui;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IssuancePolicyFormTest {
    @Test void bandCommandHasOnlyBoundsAndCannotSwitchMode() {
        assertEquals("note-policy-band 1.025 2", IssuancePolicyForm.bandCommand("peg", "1.0250", "2.0000"));
        assertEquals("note-policy-band 2 2", IssuancePolicyForm.bandCommand("peg", "2", "2"));
        for (String mode : new String[]{"float", "hard", ""})
            assertThrows(IllegalArgumentException.class, () -> IssuancePolicyForm.bandCommand(mode, "1", "2"));
    }

    @Test void rejectsReversedZeroOrUnsupportedPrecision() {
        assertThrows(IllegalArgumentException.class, () -> IssuancePolicyForm.bandCommand("peg", "2", "1"));
        assertThrows(IllegalArgumentException.class, () -> IssuancePolicyForm.bandCommand("peg", "0", "1"));
        assertThrows(IllegalArgumentException.class, () -> IssuancePolicyForm.bandCommand("peg", "0.00001", "1"));
        assertThrows(IllegalArgumentException.class, () -> IssuancePolicyForm.bandCommand("peg", "1 2", "3"));
    }
    @Test void quantityRequiresDirectionAndPositiveWholeNumber() {
        assertEquals("note-quantity-preview decrease 100", IssuancePolicyForm.quantityPreview("decrease", "00100"));
        assertTrue(IssuancePolicyForm.samePositiveWhole("0001", "1"));
        assertFalse(IssuancePolicyForm.samePositiveWhole("2", "1"));
        assertFalse(IssuancePolicyForm.samePositiveWhole("0", "0"));
        for (String raw : new String[]{"1.5", "-1", "0", "全部", "1 2"})
            assertThrows(IllegalArgumentException.class, () -> IssuancePolicyForm.quantityPreview("increase", raw));
        assertThrows(IllegalArgumentException.class, () -> IssuancePolicyForm.quantityPreview("restore", "1"));
    }
}
