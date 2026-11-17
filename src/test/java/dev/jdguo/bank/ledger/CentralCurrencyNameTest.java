package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.net.BankUiSnapshot;
import com.google.gson.JsonParser;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CentralCurrencyNameTest {
    @Test void renamePersistsAndPreservesBalancesAndId() {
        var books = BankBooks.create(BankConfig.defaults());
        UUID player = UUID.randomUUID();
        books.creditUnbacked(player, "note", BigInteger.valueOf(20));
        var balance = books.balanceExact(player, "note");
        assertTrue(books.renameCentralCurrency("大明通宝", true).success());
        var restored = BankBooks.fromJson(books.toJson(), BankConfig.defaults());
        assertEquals("note", restored.primaryVirtual().id());
        assertEquals("大明通宝", restored.primaryVirtual().name());
        assertEquals(balance, restored.balanceExact(player, "note"));
        var snapshot = JsonParser.parseString(BankUiSnapshot.json(restored, player, true, "management", false)).getAsJsonObject();
        assertEquals("大明通宝", snapshot.get("centralCurrencyName").getAsString());
    }

    @Test void rejectsUnauthorizedBlankAndAmbiguousNames() {
        var books = BankBooks.create(BankConfig.defaults());
        assertFalse(books.renameCentralCurrency("假币", false).success());
        assertFalse(books.renameCentralCurrency("", true).success());
        assertFalse(books.renameCentralCurrency("钻石", true).success());
        assertFalse(books.renameCentralCurrency("坏\n名", true).success());
        assertEquals("央行券", books.primaryVirtual().name());
    }

    @Test void migratesOnlyLegacyDefaultName() {
        var books = BankBooks.create(BankConfig.defaults());
        var json = JsonParser.parseString(books.toJson()).getAsJsonObject();
        json.remove("currencyNameVersion");
        var restored = BankBooks.fromJson(json.toString().replace("央行券", "银行券"), BankConfig.defaults());
        assertEquals("央行券", restored.primaryVirtual().name());
    }
}
