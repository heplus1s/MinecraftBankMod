package dev.jdguo.bank.credit;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.TxResult;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssuanceCollateralTest {
    private final UUID player = UUID.fromString("abababab-abab-abab-abab-abababababab");

    @Test
    void copperAndDiamondsBothCountAndALockedPledgeIsNotCountedTwice() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setReserve("copper", new BigInteger("10000000"));
        books.setReserve("diamond", new BigInteger("100000"));
        assertEquals(new BigInteger("100"), books.copperPerDiamond());
        assertEquals(new BigInteger("200000"), books.backedRoom(books.currency("note")));

        TxResult issued = books.creditBacked(player, "note", new BigInteger("1000"));
        assertTrue(issued.success(), issued.code());
        assertEquals(new BigInteger("1000"), books.supply("diamond").locked());
        assertEquals(BigInteger.ZERO, books.supply("copper").locked());
        assertEquals(new BigInteger("199000"), books.backedRoom(books.currency("note")));
        assertEquals(new BigInteger("1000"), books.balance(player, "note"));
    }

    @Test
    void aTypedBackedAmountAboveTheRoomIsRefusedWhole() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        BigInteger room = books.backedRoom(books.currency("note"));
        BigInteger liability = books.supply("note").liability();
        BigInteger locked = books.supply("diamond").locked();

        TxResult refused = books.creditBacked(player, "note", room.add(BigInteger.ONE));

        assertFalse(refused.success());
        assertEquals("over_cap", refused.code());
        assertEquals(BigInteger.ZERO, books.balance(player, "note"));
        assertEquals(liability, books.supply("note").liability());
        assertEquals(locked, books.supply("diamond").locked());
        assertEquals(room, books.backedRoom(books.currency("note")));
    }

    @Test
    void copperIsLockedOnlyAfterFreeDiamondsAreUsedUp() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertEquals(new BigInteger("1000"), books.copperPerDiamond());

        TxResult issued = books.creditBacked(player, "note", new BigInteger("1005"));

        assertTrue(issued.success(), issued.code());
        assertEquals(new BigInteger("1000"), books.supply("diamond").locked());
        assertEquals(new BigInteger("5000"), books.supply("copper").locked());
        assertEquals(new BigInteger("995"), books.backedRoom(books.currency("note")));
    }
}
