package dev.jdguo.bank.display;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutletSignTest {
    @Test
    void theOutletBoardShowsQuotesAndNotRedemptionCapacity() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        String sign = DisplayService.outletSign(books, 0).getString();

        assertTrue(sign.contains("中央银行"));
        assertTrue(sign.contains("汇率"));
        assertTrue(sign.contains("1 钻石 = "));
        assertTrue(sign.contains("铜锭"));
        assertTrue(sign.contains("银行券"));
        assertTrue(sign.contains("右键此方块开启柜台"));
        assertFalse(sign.contains("兑付能力"));
    }
}
