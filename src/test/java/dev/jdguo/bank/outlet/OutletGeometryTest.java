package dev.jdguo.bank.outlet;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutletGeometryTest {
    @Test void unicodeAndQuotedNames() {
        assertTrue(OutletNames.valid("中央银行 北京分行"));
        assertEquals("中央银行 北京分行", OutletNames.commandName("\"中央银行 北京分行\""));
        assertTrue(OutletNames.valid("东".repeat(32)));
        assertFalse(OutletNames.valid("东".repeat(33)));
        assertFalse(OutletNames.valid("§a银行"));
        assertFalse(OutletNames.valid("银行\n"));
    }
    @Test void frontRotatesAroundInteractionCenter() {
        OutletPoint o = new OutletPoint("总行", "minecraft:overworld", 0, 64, 0, 4, 2.4, true, null);
        float[][] front = {{0,-1}, {1,0}, {0,1}, {-1,0}};
        for (int n=0;n<4;n++) {
            o.setQuarterTurns(n);
            assertEquals(front[n][0], o.rotatedX(0,-1), 0.0001f);
            assertEquals(front[n][1], o.rotatedZ(0,-1), 0.0001f);
            assertEquals(0, o.rotatedX(0,0), 0.0001f);
            assertEquals(0, o.rotatedZ(0,0), 0.0001f);
        }
    }
    @Test void persistenceAndLegacyNorth() {
        BankConfig config = BankConfig.defaults();
        BankBooks b = BankBooks.create(config);
        OutletPoint o = new OutletPoint("总行", "minecraft:overworld", 1, 64, 2, 4, 2.4, true, null);
        o.setQuarterTurns(3);
        b.addOutlet(o);
        var json = b.toJson();
        assertEquals(3, BankBooks.fromJson(json, config).outlet("总行").quarterTurns());
        var legacy = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        legacy.getAsJsonArray("outlets").get(0).getAsJsonObject().remove("quarterTurns");
        assertEquals(0, BankBooks.fromJson(legacy.toString(), config).outlet("总行").quarterTurns());
    }
}
