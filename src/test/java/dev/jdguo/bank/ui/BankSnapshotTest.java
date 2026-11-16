package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankSnapshotTest {
    @Test
    void readsBalancesNotesAndAdminAccountsWithoutInventingPriceOrInterest() {
        JsonObject data = JsonParser.parseString("""
                {
                  "admin": true,
                  "currencies": [
                    {"id": "copper", "name": "铜锭", "balance": "1,000"},
                    {"id": "note", "name": "银行券", "balance": "4"}
                  ],
                  "notes": [
                    {"id": "abc", "name": "张三", "balance": "0"},
                    {"id": "def", "name": "李四", "balance": "2"}
                  ],
                  "central": [
                    {"name": "钻石", "savings": "10", "reserve": "1000", "vault": "8"}
                  ],
                  "institutions": [
                    {"name": "商店经营账户", "detail": "银行券  3"},
                    {"name": "土地出让账户", "detail": "银行券  1"}
                  ],
                  "personal": "张三",
                  "regime": "FLOAT"
                }
                """).getAsJsonObject();

        assertEquals("铜锭", BankSnapshot.currencies(data).get(0).name());
        assertEquals(1, BankSnapshot.heldNotes(data).size());
        assertEquals("李四", BankSnapshot.heldNotes(data).get(0).name());
        assertNull(BankSnapshot.heldNotes(data).get(0).price());
        assertEquals("1000", BankSnapshot.central(data).get(0).reserve());
        assertEquals("8", BankSnapshot.central(data).get(0).vault());
        assertEquals("商店经营账户", BankSnapshot.institutions(data).get(0).name());
        assertEquals("土地出让账户", BankSnapshot.institutions(data).get(1).name());
        assertEquals("浮动汇率制", BankSnapshot.regimeLabel("FLOAT"));
        assertFalse(BankSnapshot.has(data, "interest"));
        assertFalse(BankSnapshot.has(data, "issuable"));
        assertTrue(BankSnapshot.admin(data));
        data.addProperty("frozen", true);
        assertNull(BankSnapshot.notePaused(data));
    }

    @Test
    void personalRedemptionPauseComesFromTheNoteNotTheCentralFlag() {
        JsonObject paused = JsonParser.parseString("""
                {"frozen": false, "ownNote": {"frozen": true}}
                """).getAsJsonObject();
        assertEquals(Boolean.TRUE, BankSnapshot.notePaused(paused));
        JsonObject open = JsonParser.parseString("""
                {"frozen": true, "ownNote": {"frozen": false}}
                """).getAsJsonObject();
        assertEquals(Boolean.FALSE, BankSnapshot.notePaused(open));
        JsonObject unnamed = JsonParser.parseString("""
                {"ownNote": {"status": "兑付已暂停"}}
                """).getAsJsonObject();
        assertEquals(Boolean.TRUE, BankSnapshot.notePaused(unnamed));
        assertNull(BankSnapshot.notePaused(new JsonObject()));
    }
}
