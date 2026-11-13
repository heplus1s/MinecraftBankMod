package dev.jdguo.bank.net;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BankUiQuantityTest {
    @Test
    void picksExactSheetsAndLeavesAFaceThatDoesNotDivide() {
        var piles = List.of(
                new BankUiService.Pile(0, BigInteger.ONE, 64),
                new BankUiService.Pile(1, BigInteger.TEN, 1));

        var ten = BankUiService.pickSheets(piles, BigInteger.TEN);
        assertEquals(1, ten.size());
        assertEquals(0, ten.get(0).slot());
        assertEquals(10, ten.get(0).sheets());

        var all = BankUiService.pickSheets(piles, BigInteger.valueOf(74));
        assertEquals(2, all.size());
        assertEquals(64, all.get(0).sheets());
        assertEquals(1, all.get(1).sheets());

        assertNull(BankUiService.pickSheets(List.of(new BankUiService.Pile(1, BigInteger.TEN, 1)), BigInteger.valueOf(64)));
        assertNull(BankUiService.pickSheets(piles, BigInteger.ZERO));
    }
}
