package dev.jdguo.bank.ledger;

import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.NoteRegime;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RecursiveClearingTest {
    @Test void realLedgerEndToEndRegression() { RecursiveClearingRegression.main(new String[0]); }

    @Test void pledgedAndListedCurrencyCannotBeSpentTwice() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID leafOwner = UUID.randomUUID(), owner = UUID.randomUUID();
        assertTrue(books.createPersonalNote(leafOwner, "叶", NoteRegime.FLOAT, "diamond", BigInteger.ONE).success());
        var leaf = books.personalNote(leafOwner);
        books.issuePersonalNoteUnbacked(leafOwner, BigInteger.TEN);
        books.deposit(leafOwner, "diamond", BigInteger.TEN);
        assertTrue(books.depositPersonalReserveExact(leafOwner, BigDecimal.TEN).success());
        assertTrue(books.createPersonalNote(owner, "枝", NoteRegime.FLOAT, "personal:" + leaf.id(), BigInteger.ONE).success());
        books.issuePersonalNoteUnbacked(owner, BigInteger.TEN);
        assertTrue(books.transferPersonal(leafOwner, owner, "叶", BigInteger.valueOf(5)).success());
        assertTrue(books.listPersonal(owner, "叶", BigInteger.valueOf(5)).success());
        String before = books.toJson();
        assertFalse(books.depositPersonalReserveExact(owner, BigDecimal.ONE).success());
        assertEquals(before, books.toJson());
        books.listPersonal(owner, "叶", BigInteger.ZERO);
        assertTrue(books.depositPersonalReserveExact(owner, new BigDecimal("5")).success());
        assertTrue(books.listPersonal(owner, "枝", BigInteger.valueOf(3)).success());
        before = books.toJson();
        assertFalse(books.settlePersonalToSystem(owner, books.personalNote(owner).id(), new BigDecimal("8"), "shop", "reserved").success());
        assertEquals(before, books.toJson());
        assertTrue(books.settlePersonalToSystem(owner, books.personalNote(owner).id(), new BigDecimal("6"), "shop", "free").success());
        assertEquals(BigInteger.valueOf(3), books.personalOffered(books.personalNote(owner).id(), owner));
        books.checkInvariants();
    }

    @Test void oldSaveDoesNotAcquireAnAutomaticAssetMovingPolicy() {
        BankBooks books = BankBooks.create(BankConfig.defaults()); UUID owner = UUID.randomUUID();
        books.createPersonalNote(owner, "旧", NoteRegime.HARD, "diamond", BigInteger.ONE);
        books.issuePersonalNoteUnbacked(owner, BigInteger.TEN);
        var json = JsonParser.parseString(books.toJson()).getAsJsonObject(); json.remove("recursiveClearing");
        BankBooks loaded = BankBooks.fromJson(json.toString(), BankConfig.defaults());
        String before = loaded.toJson();
        assertEquals("no_change", loaded.rebalancePersonalPolicy(owner).code());
        assertEquals(before, loaded.toJson());
        assertEquals(0, loaded.personalRecovered(loaded.personalNote(owner).id()).signum());
        loaded.checkInvariants();
    }
}
