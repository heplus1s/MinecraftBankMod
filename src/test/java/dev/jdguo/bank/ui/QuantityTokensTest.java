package dev.jdguo.bank.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuantityTokensTest {
    @Test
    void wholeWordBecomesTheServerToken() {
        assertEquals("all", QuantityTokens.token("全部"));
        assertEquals("all", QuantityTokens.token(" all "));
        assertEquals("5", QuantityTokens.token("5"));
        assertEquals("1", QuantityTokens.token("  "));
    }
}
