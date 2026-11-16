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

    @Test void configuredIssuanceUsesExactTargetAndWithdrawsOnlyExcess() {
        BankBooks books = BankBooks.create(BankConfig.defaults()); UUID owner = UUID.randomUUID();
        books.createPersonalNote(owner, "价", NoteRegime.HARD, "diamond", BigInteger.ONE);
        books.deposit(owner, "diamond", BigInteger.valueOf(5));
        assertTrue(books.depositPersonalReserveExact(owner, new BigDecimal("5")).success());
        assertTrue(books.setPersonalPolicy(owner, NoteRegime.HARD, new BigDecimal("2"), new BigDecimal("2"), new BigDecimal("2")).success());
        assertFalse(books.issuePersonalNote(owner, BigInteger.valueOf(3)).success());
        assertTrue(books.issuePersonalNote(owner, BigInteger.TWO).success());
        assertEquals(0, books.personalRequiredBackingExact(books.personalNote(owner)).compareTo(new BigDecimal("4")));
        assertTrue(books.withdrawPersonalReserveExact(owner, new BigDecimal("0.5")).success());
        assertEquals(0, books.effectiveBackingExact(books.personalNote(owner)).compareTo(new BigDecimal("4.5")));
        assertFalse(books.withdrawPersonalReserveExact(owner, new BigDecimal("0.6")).success());
        assertFalse(books.issuePersonalNote(owner, BigInteger.ONE).success());
        books.checkInvariants();
    }

    @Test void pendingDeliveryLocksLegacyAndNewMutationPathsUntilDurableCompletion() {
        BankBooks books = BankBooks.create(BankConfig.defaults()); UUID owner = UUID.randomUUID();
        books.createPersonalNote(owner, "锁", NoteRegime.FLOAT, "diamond", BigInteger.ONE);
        books.issuePersonalNoteUnbacked(owner, BigInteger.TEN);
        books.deposit(owner, "diamond", BigInteger.TEN); books.depositPersonalReserveExact(owner, BigDecimal.TEN);
        UUID id = books.personalNote(owner).id();
        assertTrue(books.settlePersonalToSystem(owner, id, BigDecimal.ONE, "land-grant", "pending").success());
        String before = books.toJson();
        assertFalse(books.issuePersonalNoteUnbacked(owner, BigInteger.ONE).success());
        assertFalse(books.issuePersonalNote(owner, BigInteger.ONE).success());
        assertFalse(books.retirePersonalNote(owner, BigInteger.ONE).success());
        assertFalse(books.withdrawPersonalReserve(owner, BigInteger.ONE).success());
        assertFalse(books.withdrawCentralReserve(owner, BigInteger.ONE).success());
        assertFalse(books.depositPersonalReserve(owner, BigInteger.ONE).success());
        assertFalse(books.depositCentralReserve(owner, BigInteger.ONE).success());
        assertFalse(books.switchPersonalRegime(owner, NoteRegime.HARD, "diamond").allowed());
        assertFalse(books.declareBankruptcy(owner, "锁", true).success());
        assertFalse(books.setPersonalPolicy(owner, NoteRegime.HARD, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE).success());
        assertFalse(books.transferPersonalExact(owner, UUID.randomUUID(), "personal:" + id, BigDecimal.ONE).success());
        assertFalse(books.issuePersonalPaper(owner, id, BigInteger.ONE).result().success());
        assertThrows(IllegalStateException.class, () -> books.setPersonalReserve(owner, "diamond", BigInteger.ZERO));
        assertEquals(before, books.toJson());
        assertEquals(0, books.systemAvailableBalance("land-grant", "diamond").signum());
        assertTrue(books.reversePersonalSettlement("pending").success());
        assertFalse(books.noteHasPending(id));
        assertEquals(0, books.personalRecovered(id).signum());
        books.checkInvariants();
    }
}
