package dev.jdguo.bank.ledger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.net.BankUiSnapshot;
import dev.jdguo.bank.currency.CurrencyDef;
import dev.jdguo.bank.currency.CurrencyKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuoteAndReserveTest {
    private final UUID player = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private final UUID other = UUID.fromString("88888888-8888-8888-8888-888888888888");

    @Test
    void noteRedemptionCancelsCirculationAndPaysFromReservesNotOtherDeposits() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(other, "diamond", new BigInteger("7"));
        books.creditHeld(player, "note", new BigInteger("4"));
        BigInteger otherDiamonds = books.balance(other, "diamond");
        BigInteger reserve = books.supply("diamond").reserve();
        BigDecimal vault = books.supply("diamond").depositVaultExact();

        BigDecimal notesBefore = books.balanceExact(player, "note");
        BigDecimal quote = books.unitsPer("diamond", "note");
        BigDecimal cost = books.settlementCost("note", "diamond", BigInteger.ONE);
        TxResult redeemed = books.exchange(player, "note", "diamond", BigInteger.ONE);

        assertTrue(redeemed.success(), redeemed.code());
        assertTrue(quote != null && quote.signum() > 0);
        assertEquals(0, notesBefore.subtract(cost).compareTo(books.balanceExact(player, "note")));
        assertTrue(cost.compareTo(BigDecimal.ONE) < 0);
        assertEquals(BigInteger.ONE, books.balance(player, "diamond"));
        assertEquals(otherDiamonds, books.balance(other, "diamond"));
        assertEquals(reserve.subtract(BigInteger.ONE), books.supply("diamond").reserve());
        assertEquals(0, vault.add(BigDecimal.ONE).compareTo(books.supply("diamond").depositVaultExact()));
        assertTrue(books.centralM1().compareTo(new BigInteger("4")) < 0);
    }

    @Test
    void depositsChangeTotalHoldingsButInternalReserveTransfersDoNot() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertEquals(0, new BigDecimal("1000").compareTo(books.unitsPer("copper", "diamond")));

        books.deposit(player, "copper", new BigInteger("1000"));
        assertEquals(0, new BigDecimal("1001").compareTo(books.unitsPer("copper", "diamond")));
        assertEquals(new BigInteger("1000000"), books.supply("copper").reserve());

        assertTrue(books.transferTreasury(player, "copper", new BigDecimal("1000"), true).success());
        assertTrue(books.transferReserve("copper", new BigDecimal("1000"), true).success());
        assertEquals(0, new BigDecimal("1001").compareTo(books.unitsPer("copper", "diamond")));
        assertEquals(new BigInteger("1001000"), books.supply("copper").reserve());
        assertEquals(0, BigDecimal.ZERO.compareTo(books.supply("copper").depositVaultExact()));
    }

    @Test
    void reserveTransferRefusesAShortDepositAndDoesNotMintMetal() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        BigInteger copperReserve = books.supply("copper").reserve();
        BigDecimal vault = books.supply("copper").depositVaultExact();

        TxResult refused = books.transferReserve("copper", new BigDecimal("1.0000"), true);
        assertEquals("insufficient_treasury", refused.code());
        assertEquals(copperReserve, books.supply("copper").reserve());
        assertEquals(0, vault.compareTo(books.supply("copper").depositVaultExact()));

        books.deposit(player, "copper", new BigInteger("5"));
        assertTrue(books.transferTreasury(player, "copper", new BigDecimal("5"), true).success());
        TxResult moved = books.transferReserve("copper", new BigDecimal("2.5000"), true);
        assertTrue(moved.success(), moved.code());
        assertEquals(0, new BigDecimal("2.5000").compareTo(books.supply("copper").reserveExact().subtract(new BigDecimal(copperReserve))));
        assertEquals(0, new BigDecimal("2.5000").compareTo(books.treasuryExact("copper")));
        assertEquals(BigInteger.ZERO, books.balance(player, "copper"));

        BigInteger afterMove = books.supply("copper").reserve();
        TxResult back = books.transferReserve("copper", new BigDecimal("10000000"), false);
        assertEquals("insufficient_reserves", back.code());
        assertEquals(afterMove, books.supply("copper").reserve());
    }

    @Test
    void shopTransferAcceptsFourDecimalsAndRefusesAShortBalance() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditHeld(player, "note", new BigDecimal("1.5000"));
        TxResult paid = books.transferShop(player, "note", new BigDecimal("1.2500"), true);
        assertTrue(paid.success(), paid.code());
        assertEquals(0, new BigDecimal("0.2500").compareTo(books.balanceExact(player, "note")));

        TxResult over = books.transferShop(player, "note", new BigDecimal("0.2501"), true);
        assertEquals("insufficient_funds", over.code());
        assertEquals(0, new BigDecimal("0.2500").compareTo(books.balanceExact(player, "note")));

        UUID series = UUID.randomUUID();
        assertTrue(books.createPersonalNote(player, "泉", false, BigInteger.ONE).success());
        series = books.personalNote(player).id();
        books.setPersonalReserve(player, "diamond", new BigInteger("3"));
        assertTrue(books.issuePersonalNote(player, new BigInteger("2")).success());
        TxResult personal = books.transferShop(player, "personal:" + series, new BigDecimal("1.5000"), true);
        assertTrue(personal.success(), personal.code());
        assertEquals(0, new BigDecimal("0.5000").compareTo(books.personalHoldingExact(series, player)));
        TxResult personalShort = books.transferShop(player, "personal:" + series, new BigDecimal("0.5001"), true);
        assertEquals("insufficient_funds", personalShort.code());
        assertEquals(0, new BigDecimal("0.5000").compareTo(books.personalHoldingExact(series, player)));
    }

    @Test
    void aBookWithNoQuoteDoesNotInventOne() {
        BigDecimal alpha = new BigDecimal("1000");
        List<CurrencyDef> list = List.of(
                new CurrencyDef("copper", CurrencyKind.COMMODITY, "minecraft:copper_ingot",
                        "铜锭", "Copper", "gold", BigInteger.ZERO, BigInteger.ZERO, alpha,
                        false, null, null, null, null),
                new CurrencyDef("diamond", CurrencyKind.COMMODITY, "minecraft:diamond",
                        "钻石", "Diamond", "aqua", BigInteger.ZERO, BigInteger.ZERO, alpha,
                        false, null, null, null, null),
                new CurrencyDef("note", CurrencyKind.VIRTUAL, "minecraft:paper",
                        "央行券", "bank note", "yellow", BigInteger.ZERO, BigInteger.ZERO, alpha,
                        true, "diamond", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        BankBooks books = BankBooks.create(new BankConfig("diamond", new BigDecimal("0.02"), new BigDecimal("0.02"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, 4.0,
                true, true, true, true, true, list));
        assertNull(books.unitsPer("copper", "diamond"));
        assertNull(books.unitsPer("note", "diamond"));
        assertNull(books.copperPerDiamond());
    }

    @Test
    void aSavedBookWithoutStoredQuotesRecoversTheRateTheBooksAlreadyImplied() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        JsonObject root = JsonParser.parseString(books.toJson()).getAsJsonObject();
        root.remove("liveCopperPerDiamond");
        root.remove("liveNotesPerDiamond");

        BankBooks loaded = BankBooks.fromJson(root.toString(), BankConfig.defaults());

        assertEquals(0, new BigDecimal("1000").compareTo(loaded.unitsPer("copper", "diamond")));
        assertNull(loaded.unitsPer("note", "diamond"));
        assertNull(loaded.unitsPer("diamond", "note"));
        JsonObject plain = JsonParser.parseString(BankUiSnapshot.json(loaded, player, false, "savings", false)).getAsJsonObject();
        JsonObject admin = JsonParser.parseString(BankUiSnapshot.json(loaded, player, true, "management", false)).getAsJsonObject();
        assertEquals("1000", plain.get("copperPerDiamond").getAsString());
        assertFalse(plain.has("diamondsPerNote"));
        assertEquals("1000", admin.get("copperPerDiamond").getAsString());
        assertFalse(admin.has("diamondsPerNote"));
        assertEquals("management", admin.get("page").getAsString());
        assertTrue(admin.get("admin").getAsBoolean());
        assertFalse(plain.get("admin").getAsBoolean());
    }

    @Test
    void aStoredVolumeNudgeIsNotKeptWhenTheBooksAreReadAgain() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditHeld(player, "note", new BigInteger("1000"));
        JsonObject root = JsonParser.parseString(books.toJson()).getAsJsonObject();
        root.addProperty("liveNotesPerDiamond", "9");
        root.addProperty("diamondsPerNote", "9");

        BankBooks loaded = BankBooks.fromJson(root.toString(), BankConfig.defaults());

        assertEquals(0, new BigDecimal("3").compareTo(loaded.unitsPer("diamond", "note")));
        assertEquals(0, new BigDecimal("0.3333").compareTo(loaded.unitsPer("note", "diamond")));
        assertEquals(0, new BigDecimal("1000").compareTo(loaded.unitsPer("copper", "diamond")));
    }
}
