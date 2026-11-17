package dev.jdguo.bank.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankUiHandshakeTest {
    @Test void lateChannelRegistrationDoesNotLoseHello() {
        var state = new BankUiHandshake();
        for (int i = 0; i < 200; i++) assertFalse(state.poll(true, false));
        assertTrue(state.poll(true, true));
    }
    @Test void retriesAreBoundedAndSpaced() {
        var state = new BankUiHandshake();
        int sends = 0;
        for (int i = 0; i < 1000; i++) if (state.poll(true, true)) sends++;
        assertEquals(5, sends);
        state.reset();
        assertTrue(state.poll(true, true));
        for (int i = 0; i < 19; i++) assertFalse(state.poll(true, true));
        assertTrue(state.poll(true, true));
    }
    @Test void disconnectAndAcknowledgementStopSends() {
        var state = new BankUiHandshake();
        assertFalse(state.poll(false, true));
        state.acknowledged();
        assertFalse(state.poll(true, true));
        state.reset();
        assertTrue(state.poll(true, true));
    }
}
