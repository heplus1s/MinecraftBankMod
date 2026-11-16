package dev.jdguo.bank.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BankExchangeTest {
    @Test
    void hardCurrenciesUseExchangeAndNotesDoNotTouchReserves() {
        assertEquals("exchange copper note 5", BankExchange.command("copper", "note", "5"));
        assertEquals("note-sell 张三 5", BankExchange.command("pn:张三", "note", "5"));
        assertEquals("note-buy 张三 5", BankExchange.command("note", "pn:张三", "5"));
        assertEquals("note-swap 张三 李四 2", BankExchange.command("pn:张三", "pn:李四", "2"));
        assertNull(BankExchange.command("pn:张三", "copper", "1"));
        assertNull(BankExchange.command("diamond", "pn:张三", "1"));
        assertNull(BankExchange.command("note", "note", "1"));
    }
}
