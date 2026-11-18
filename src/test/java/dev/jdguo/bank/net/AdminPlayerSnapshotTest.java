package dev.jdguo.bank.net;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.market.NoteRegime;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminPlayerSnapshotTest {
    @Test void exactBalancesReservesAndHoldingsAreSeparate() {
        var books = BankBooks.create(BankConfig.defaults());
        UUID issuer = UUID.randomUUID(), holder = UUID.randomUUID();
        books.deposit(issuer, "diamond", BigInteger.valueOf(1234567));
        books.deposit(issuer, "copper", BigInteger.valueOf(321));
        assertTrue(books.createPersonalNote(issuer, "龙币", NoteRegime.FLOAT, "diamond", BigInteger.ONE).success());
        books.setPersonalReserve(issuer, "diamond", BigInteger.valueOf(50));
        assertTrue(books.issuePersonalNoteUnbacked(issuer, BigInteger.valueOf(2000)).success());
        var note = books.personalNote(issuer);
        assertTrue(books.transferPersonalExact(issuer, holder, "personal:" + note.id(), new BigDecimal("12.3456")).success());
        var result = AdminPlayerSnapshot.page(books, Map.of(issuer, "甲", holder, "乙"), "甲", 0);
        var row = result.getAsJsonArray("rows").get(0).getAsJsonObject();
        assertEquals("1234567", row.get("diamond").getAsString());
        assertEquals("321", row.get("copper").getAsString());
        var issued = row.getAsJsonArray("issued").get(0).getAsJsonObject();
        assertEquals("2000", issued.get("amount").getAsString());
        assertEquals("50", issued.get("reserveAmount").getAsString());
        assertEquals("钻石", issued.get("reserveType").getAsString());
        var holderRow = AdminPlayerSnapshot.page(books, Map.of(holder,"乙"), "", 0).getAsJsonArray("rows").get(0).getAsJsonObject();
        assertEquals(0, holderRow.getAsJsonArray("issued").size());
        assertEquals("12.3456", holderRow.getAsJsonArray("holdings").get(0).getAsJsonObject().get("amount").getAsString());
        assertTrue(books.customerIds().contains(holder));
    }
    @Test void stablePagingSearchClampsAndZeroBalancePlayers() {
        var books = BankBooks.create(BankConfig.defaults()); Map<UUID,String> names = new HashMap<>();
        for (int i=0; i<19; i++) names.put(new UUID(0,i), String.format("Player%02d",i));
        var last = AdminPlayerSnapshot.page(books,names,"", Integer.MAX_VALUE);
        assertEquals(2,last.get("page").getAsInt()); assertEquals(3,last.getAsJsonArray("rows").size());
        assertEquals("Player16",last.getAsJsonArray("rows").get(0).getAsJsonObject().get("name").getAsString());
        var found = AdminPlayerSnapshot.page(books,names,"pLaYeR01",-10);
        assertEquals(1,found.get("total").getAsInt());
        assertEquals("0",found.getAsJsonArray("rows").get(0).getAsJsonObject().get("note").getAsString());
        assertEquals(0,AdminPlayerSnapshot.page(books,names,"missing",0).get("total").getAsInt());
        assertEquals(1,AdminPlayerSnapshot.page(books,names,"missing",0).get("pages").getAsInt());
    }
    @Test void reserveOnlyCustomerIsIncludedAndSnapshotIsReadOnly() {
        var books = BankBooks.create(BankConfig.defaults()); UUID reserveOnly=UUID.randomUUID();
        books.setPersonalReserve(reserveOnly,"copper",BigInteger.TEN);
        assertTrue(books.customerIds().contains(reserveOnly));
        var before=books.toJson().toString();
        AdminPlayerSnapshot.page(books,Map.of(reserveOnly,"离线玩家"),"",0);
        assertEquals(before,books.toJson().toString());
    }
    @Test void managementActionCannotBeForgedByOrdinaryPlayer() {
        assertFalse(BankUiAccess.actionAllowed("admin-players",false,true));
        assertFalse(BankUiAccess.actionAllowed("admin-players",true,false));
        assertFalse(BankUiAccess.actionAllowed("admin-players-close",false,false));
        assertTrue(BankUiAccess.actionAllowed("admin-players",true,true));
    }
}
