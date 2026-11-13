package dev.jdguo.bank.ledger;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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

    @Test
    void aggregatesRoundToSixDecimalsAndLeaveWholeNumbersPlain() {
        assertEquals("0", CompactFormat.formatAggregate(BigInteger.ZERO));
        assertEquals("1,000", CompactFormat.formatAggregate(new BigInteger("1000")));
        assertEquals("4.004123", CompactFormat.formatAggregate(new BigDecimal("4.0041234567")));
        assertEquals("1.234568", CompactFormat.formatAggregate(new BigDecimal("1.2345675")));
        assertEquals("0.000001", CompactFormat.formatAggregate(new BigDecimal("0.0000005")));
        assertEquals("0", CompactFormat.formatAggregate(new BigDecimal("0.0000004")));
        assertEquals("-1,500,000.5", CompactFormat.formatAggregate(new BigDecimal("-1500000.50000004")));
    }
}
