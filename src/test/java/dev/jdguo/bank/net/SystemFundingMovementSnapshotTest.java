package dev.jdguo.bank.net;

import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SystemFundingMovementSnapshotTest {
    @Test void olderClientsOnlySeeIncomingFundingWhileNewClientsSeeBothDirections() {
        UUID admin = UUID.randomUUID();
        BankBooks b = BankBooks.create(BankConfig.defaults());
        BigDecimal five = new BigDecimal("5");
        b.creditHeld(admin, "note", five);
        assertTrue(b.transferTreasury(admin, "note", five, true).success());
        assertTrue(b.fundSystemAccount(admin, true, "shop", five, five, BigDecimal.ZERO).success());
        assertTrue(b.recallSystemAccount(admin, true, "shop", BigDecimal.ONE, b.systemRecallCapacity("shop")).success());

        var adminSnapshot = JsonParser.parseString(BankUiSnapshot.json(b, admin, true, "management", false)).getAsJsonObject();
        assertTrue(adminSnapshot.get("systemFundingRecallSupported").getAsBoolean());
        assertEquals("4", adminSnapshot.getAsJsonObject("shopSystemFunds").get("recallAvailable").getAsString());
        assertEquals(1, adminSnapshot.getAsJsonArray("systemFundingLog").size());
        var movements = adminSnapshot.getAsJsonArray("systemFundingMovements");
        assertEquals(2, movements.size());
        assertEquals("recall", movements.get(1).getAsJsonObject().get("direction").getAsString());
        assertEquals("4", movements.get(1).getAsJsonObject().get("availableAfter").getAsString());
        var playerSnapshot = JsonParser.parseString(BankUiSnapshot.json(b, admin, false, "management", false)).getAsJsonObject();
        assertFalse(playerSnapshot.has("systemFundingRecallSupported"));
        assertFalse(playerSnapshot.has("systemFundingMovements"));
    }
}
