package dev.jdguo.bank.ledger;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactFormatTest {
    @Test
    void smallNumbersStayExactAndHugeNumbersGoScientific() {
        assertEquals("0", CompactFormat.format(BigInteger.ZERO));
        assertEquals("1,000", CompactFormat.format(BigInteger.valueOf(1000)));
        assertEquals("100k", CompactFormat.format(BigInteger.valueOf(100_000)));
        assertEquals("1.5M", CompactFormat.format(BigInteger.valueOf(1_500_000)));
        String huge = CompactFormat.format(BigInteger.TEN.pow(40));
        assertTrue(huge.startsWith("1"), huge);
        assertTrue(huge.contains("e40"), huge);
    }
}
