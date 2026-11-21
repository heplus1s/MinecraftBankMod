package dev.jdguo.bank.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArcadeReverseQuoteTest {
    @Test void exactDebitChoosesLowestAmountOnPrecisionPlateau(){
        var cost=ArcadeClearing.findExactDebit(10_000,new BigDecimal("0.2100"),
                amount->amount.multiply(new BigDecimal("0.4")).setScale(4,RoundingMode.DOWN));
        assertEquals(new BigDecimal("0.5250"),cost);
    }

    @Test void skipsUnrepresentableChargeAndChecksCollateralCeiling(){
        assertNull(ArcadeClearing.findExactDebit(10_000,new BigDecimal("0.0003"),
                amount->amount.multiply(new BigDecimal("2"))));
        assertNull(ArcadeClearing.findExactDebit(10_000,new BigDecimal("0.4000"),
                amount->amount.compareTo(new BigDecimal("0.2000"))>0?null:amount));
        assertEquals(new BigDecimal("0.2000"),ArcadeClearing.findExactDebit(10_000,new BigDecimal("0.2000"),
                amount->amount.compareTo(new BigDecimal("0.2000"))>0?null:amount));
    }
}
