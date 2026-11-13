package dev.jdguo.bank.persistence;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.outlet.OutletPoint;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotTest {
    @Test
    void jsonRoundTripKeepsHugeBalancesChecksAndOutlets() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID player = UUID.randomUUID();
        BigInteger huge = new BigInteger("123456789012345678901234567890");
        books.creditUnbacked(player, "note", huge);
        var issued = books.issueCheck(player, "note", new BigInteger("999"), "Ada", null, true);
        books.addOutlet(new OutletPoint("主楼", "minecraft:overworld", 10, 64, -3, 4.5, 2.4, true, null));
        books.setFrozen(true);
        books.setFeeFraction(new java.math.BigDecimal("0.05"));

        BankBooks loaded = BankBooks.fromJson(books.toJson(), BankConfig.defaults());

        assertEquals(huge.subtract(new BigInteger("999")), loaded.balance(player, "note"));
        assertEquals(huge.add(new BigInteger("1000")), loaded.supply("note").liability());
        assertEquals("OUTSTANDING", loaded.check(issued.instrumentId()).status().name());
        assertEquals("主楼", loaded.outlet("主楼").name());
        assertEquals(10, loaded.outlet("主楼").x());
        assertTrue(loaded.frozen());
        assertEquals(0, new java.math.BigDecimal("0.05").compareTo(loaded.feeFraction()));
        assertEquals(0, new java.math.BigDecimal("1000").compareTo(loaded.unitsPer("copper", "diamond")));
        assertEquals("diamond", loaded.currency("note").backing());
        loaded.checkInvariants();
    }

    @org.junit.jupiter.api.Test
    void configRoundTripKeepsTheDatapackDefaults() {
        var config = dev.jdguo.bank.currency.ConfigJson.parse(
                dev.jdguo.bank.currency.ConfigJson.write(dev.jdguo.bank.currency.BankConfig.defaults()));
        org.junit.jupiter.api.Assertions.assertEquals("diamond", config.numeraire());
        org.junit.jupiter.api.Assertions.assertEquals(0, new java.math.BigDecimal("0.02").compareTo(config.feeFraction()));
        org.junit.jupiter.api.Assertions.assertNull(config.clampMax());
        org.junit.jupiter.api.Assertions.assertEquals(3, config.currencies().size());
        org.junit.jupiter.api.Assertions.assertTrue(config.currency("note").issuable());
    }
}
