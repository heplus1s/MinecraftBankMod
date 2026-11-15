package dev.jdguo.bank.credit;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.currency.CurrencyDef;
import dev.jdguo.bank.currency.CurrencyKind;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.TxResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssuanceCollateralTest {
    private final UUID player = UUID.fromString("abababab-abab-abab-abab-abababababab");

    @Test
    void copperAndDiamondsBothCountAndALockedPledgeIsNotCountedTwice() {
        BankBooks books = wideBook();
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

    private static BankBooks wideBook() {
        BigDecimal alpha = new BigDecimal("1000");
        List<CurrencyDef> list = List.of(
                new CurrencyDef("copper", CurrencyKind.COMMODITY, "minecraft:copper_ingot",
                        "铜锭", "Copper", "gold", new BigInteger("10000000"), new BigInteger("10000000"), alpha,
                        false, null, null, null, null),
                new CurrencyDef("diamond", CurrencyKind.COMMODITY, "minecraft:diamond",
                        "钻石", "Diamond", "aqua", new BigInteger("100000"), new BigInteger("100000"), alpha,
                        false, null, null, null, null),
                new CurrencyDef("note", CurrencyKind.VIRTUAL, "minecraft:paper",
                        "银行券", "bank note", "yellow", new BigInteger("1000"), new BigInteger("1000"), alpha,
                        true, "diamond", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        return BankBooks.create(new BankConfig("diamond", new BigDecimal("0.02"), new BigDecimal("0.02"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, 4.0,
                true, true, true, true, true, list));
    }
}
