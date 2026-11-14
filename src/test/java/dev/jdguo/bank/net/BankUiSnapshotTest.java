package dev.jdguo.bank.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import org.junit.jupiter.api.Test;

import dev.jdguo.bank.ledger.CompactFormat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankUiSnapshotTest {
    @Test
    void snapshotReportsM1WithoutCountingIssuanceReservesAsSavings() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID player = UUID.randomUUID();
        books.deposit(player, "diamond", BigInteger.TEN);
        books.creditUnbacked(player, "note", new BigInteger("4"));
        String json = BankUiSnapshot.json(books, player, false, "savings", false);
        var o = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("4", o.get("m1").getAsString());
        assertEquals("0", o.get("m0").getAsString());
        BigDecimal exactM2 = books.centralM2();
        assertEquals(CompactFormat.formatAggregate(exactM2), o.get("m2").getAsString());
        assertEquals(0, exactM2.setScale(6, RoundingMode.HALF_UP)
                .compareTo(new BigDecimal(o.get("m2").getAsString().replace(",", ""))));
        assertEquals(exactM2, books.centralM2());
        assertFalse(o.has("institutions"));
        assertEquals("10", o.get("vaultDiamond").getAsString());
        assertEquals("1000", o.get("issuanceDiamond").getAsString());
        assertFalse(o.get("savings").getAsString().equals(o.get("issuanceDiamond").getAsString()));
        assertTrue(o.get("m2").getAsString().length() > 0);
        var names = new java.util.ArrayList<String>();
        o.getAsJsonArray("currencies").forEach(row -> names.add(row.getAsJsonObject().get("name").getAsString()));
        assertTrue(names.contains("钻石"));
        assertTrue(names.contains("铜锭"));
        assertTrue(names.contains("银行券"));
        assertFalse(names.contains("diamond"));
        assertFalse(names.contains("copper"));
        JsonObject diamond = null;
        for (var row : o.getAsJsonArray("accounts")) {
            if ("钻石".equals(row.getAsJsonObject().get("name").getAsString())) {
                diamond = row.getAsJsonObject();
            }
        }
        assertNotNull(diamond);
        assertEquals("10", diamond.get("amount").getAsString());
        JsonObject reserve = null;
        for (var row : o.getAsJsonArray("central")) {
            if ("钻石".equals(row.getAsJsonObject().get("name").getAsString())) {
                reserve = row.getAsJsonObject();
            }
        }
        assertNotNull(reserve);
        assertEquals(dev.jdguo.bank.ledger.CompactFormat.format(books.supply("diamond").reserve()), reserve.get("reserve").getAsString());
        assertFalse(reserve.get("reserve").getAsString().equals(reserve.get("savings").getAsString()));
    }
}
