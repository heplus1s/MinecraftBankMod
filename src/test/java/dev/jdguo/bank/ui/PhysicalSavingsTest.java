package dev.jdguo.bank.ui;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PhysicalSavingsTest {
    @Test void separatesWithoutLosingFraction() {
        assertEquals("可取实物：9 件；零散账面权益：0.032（保留，不作为物品拆分）", PhysicalSavings.describe("9.032"));
        assertEquals("可取实物：1000 件", PhysicalSavings.describe("1,000"));
        assertTrue(PhysicalSavings.describe("0.0001").contains("0.0001"));
    }
}
