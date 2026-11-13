package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankLedgerTest {
    private final UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void commodityDepositFillsTheSavingsVaultAndLeavesIssuanceReserves() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        BigInteger issuance = books.supply("copper").reserve();
        BigInteger noteM1 = books.centralM1();

        TxResult result = books.deposit(player, "copper", BigInteger.valueOf(64));

        assertTrue(result.success());
        assertEquals(BigInteger.valueOf(64), books.balance(player, "copper"));
        assertEquals(BigInteger.valueOf(64), books.supply("copper").depositVault());
        assertEquals(issuance, books.supply("copper").reserve());
        assertEquals(noteM1, books.centralM1());
        books.checkInvariants();
    }

    @Test
    void debitRejectsInsufficientFundsAndDoesNotChangeBooks() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(player, "diamond", BigInteger.TEN);

        TxResult result = books.withdraw(player, "diamond", BigInteger.valueOf(11), false);

        assertFalse(result.success());
        assertEquals("insufficient_funds", result.code());
        assertEquals(BigInteger.TEN, books.balance(player, "diamond"));
        books.checkInvariants();
    }

    @Test
    void savingsWithdrawalDoesNotRaidIssuanceReserves() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(player, "diamond", BigInteger.TEN);
        BigInteger issuance = books.supply("diamond").reserve();
        BigInteger copperIssuance = books.supply("copper").reserve();

        TxResult paid = books.withdraw(player, "diamond", BigInteger.TEN, false);
        assertTrue(paid.success());
        assertEquals(BigInteger.TEN, paid.amount());
        assertEquals(BigInteger.ZERO, paid.copperPaid());
        assertEquals(BigInteger.ZERO, books.supply("diamond").depositVault());
        assertEquals(issuance, books.supply("diamond").reserve());
        assertEquals(copperIssuance, books.supply("copper").reserve());

        books.deposit(player, "diamond", BigInteger.valueOf(4));
        TxResult over = books.withdraw(player, "diamond", BigInteger.TEN, true);
        assertTrue(over.success());
        assertEquals(BigInteger.valueOf(4), over.amount());
        assertEquals(issuance, books.supply("diamond").reserve());
        books.checkInvariants();
    }

    @Test
    void commodityExchangeReclassifiesIssuanceReservesAndDoesNotChangeNoteM1() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(player, "copper", new BigInteger("5000"));
        BigInteger noteM1 = books.centralM1();
        BigInteger qty = BigInteger.ONE;
        BigInteger expected = books.cost("copper", "diamond", qty);

        TxResult result = books.exchange(player, "copper", "diamond", qty);

        assertTrue(result.success());
        assertEquals(expected, result.amount());
        assertEquals(new BigInteger("5000").subtract(expected), books.balance(player, "copper"));
        assertEquals(qty, books.balance(player, "diamond"));
        assertEquals(new BigInteger("1000000").add(expected), books.supply("copper").reserve());
        assertEquals(new BigInteger("999"), books.supply("diamond").reserve());
        assertEquals(books.balance(player, "copper"), books.supply("copper").depositVault());
        assertEquals(qty, books.supply("diamond").depositVault());
        assertEquals(noteM1, books.centralM1());
        books.checkInvariants();
    }

    @Test
    void exchangeIntoACommodityStopsAtFreeVaultHeadroom() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(player, "copper", new BigInteger("50000000"));
        books.setReserve("diamond", BigInteger.valueOf(2));

        BigInteger rate = books.copperPerDiamond();
        TxResult result = books.exchange(player, "copper", "diamond", BigInteger.TEN);

        assertTrue(result.success());
        assertEquals(rate.multiply(BigInteger.valueOf(8)), result.copperPaid());
        assertEquals(BigInteger.TWO, books.balance(player, "diamond"));
        assertEquals(new BigInteger("50000000").subtract(result.amount()).add(result.copperPaid()),
                books.balance(player, "copper"));
        books.checkInvariants();
    }

    @Test
    void frozenConvertibilityBlocksPaperIntoHardCurrencyOnly() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(player, "note", new BigInteger("1000"));
        books.deposit(player, "diamond", BigInteger.TEN);
        books.setFrozen(true);

        assertEquals("frozen", books.exchange(player, "note", "diamond", BigInteger.ONE).code());
        assertTrue(books.exchange(player, "diamond", "note", BigInteger.ONE).success());
        assertTrue(books.withdraw(player, "diamond", BigInteger.ONE, false).success());
        books.checkInvariants();
    }

    @Test
    void hugeBalanceDoesNotOverflow() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        BigInteger huge = BigInteger.TEN.pow(100);
        TxResult result = books.creditUnbacked(player, "note", huge);

        assertTrue(result.success());
        assertEquals(huge, books.balance(player, "note"));
        assertEquals(huge, books.supply("note").liability());
        assertEquals(huge, books.centralM1());
        String shown = CompactFormat.format(books.balance(player, "note"));
        assertTrue(shown.contains("e"), shown);
        books.checkInvariants();
    }

    @Test
    void paperMovesBetweenM0AndDepositsWithoutChangingM1() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(player, "note", BigInteger.valueOf(100));
        BigInteger m1 = books.centralM1();
        assertEquals(BigInteger.ZERO, books.centralM0());
        assertEquals(BigInteger.valueOf(100), m1);

        assertTrue(books.issueCentralPaper(player, BigInteger.valueOf(64)).result().success());
        assertEquals(BigInteger.valueOf(64), books.centralM0());
        assertEquals(BigInteger.valueOf(36), books.centralDeposits());
        assertEquals(m1, books.centralM1());

        assertTrue(books.redeemPaper(player, new dev.jdguo.bank.instruments.PresentedNote("note", null, BigInteger.ONE),
                BigInteger.valueOf(64)).success());
        assertEquals(BigInteger.ZERO, books.centralM0());
        assertEquals(m1, books.centralM1());
        assertEquals(new BigInteger("1000"), books.supply("diamond").reserve());
        books.checkInvariants();
    }

    @Test
    void noteDiamondConvertibilityMovesIssuanceReservesAndM1() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(player, "diamond", BigInteger.TEN);
        BigInteger issuanceBefore = books.supply("diamond").reserve();
        assertEquals(BigInteger.ZERO, books.centralM1());

        TxResult bought = books.exchange(player, "diamond", "note", BigInteger.valueOf(4));
        assertTrue(bought.success(), bought.code());
        assertEquals(BigInteger.valueOf(4), books.centralM1());
        assertEquals(issuanceBefore.add(bought.amount()), books.supply("diamond").reserve());
        assertEquals(books.balance(player, "diamond"), books.supply("diamond").depositVault());

        BigInteger issued = books.centralM1();
        TxResult sold = books.exchange(player, "note", "diamond", BigInteger.ONE);
        assertTrue(sold.success(), sold.code());
        assertEquals(issued.subtract(sold.amount()), books.centralM1());
        assertEquals(issuanceBefore.add(bought.amount()).subtract(BigInteger.ONE), books.supply("diamond").reserve());
        books.checkInvariants();
    }

    @Test
    void aggregatesKeepM0M1AndM2SeparateFromIssuableRoom() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertEquals(BigInteger.ZERO, books.centralM0());
        assertEquals(BigInteger.ZERO, books.centralM1());
        assertEquals(new BigInteger("1000"), books.backedRoom(books.currency("note")));
        books.creditUnbacked(player, "note", BigInteger.TEN);
        books.deposit(player, "diamond", BigInteger.TEN);
        assertEquals(BigInteger.TEN, books.centralM1());
        assertEquals(BigInteger.ZERO, books.centralM0());
        assertTrue(books.centralM2().compareTo(new BigDecimal(books.centralM1())) > 0);
        assertEquals(books.centralM0().add(books.centralDeposits()), books.centralM1());
        books.checkInvariants();
    }

    @Test
    void mixedOldLedgerSplitsSavingsFromIssuanceWithoutTouchingBalances() {
        BankBooks modern = BankBooks.create(BankConfig.defaults());
        modern.deposit(player, "copper", BigInteger.valueOf(64));
        modern.creditUnbacked(player, "note", BigInteger.valueOf(5));
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(modern.toJson()).getAsJsonObject();
        com.google.gson.JsonObject copper = root.getAsJsonObject("supplies").getAsJsonObject("copper");
        copper.remove("depositVault");
        copper.addProperty("reserve", new BigInteger(copper.get("reserve").getAsString()).add(BigInteger.valueOf(64)).toString());
        copper.addProperty("liability", new BigInteger("1000000").add(BigInteger.valueOf(64)).toString());
        com.google.gson.JsonObject note = root.getAsJsonObject("supplies").getAsJsonObject("note");
        note.remove("depositVault");
        note.addProperty("liability", new BigInteger(note.get("liability").getAsString()).add(new BigInteger("1000")).toString());

        BankBooks loaded = BankBooks.fromJson(root.toString(), BankConfig.defaults());

        assertEquals(BigInteger.valueOf(64), loaded.balance(player, "copper"));
        assertEquals(BigInteger.valueOf(64), loaded.supply("copper").depositVault());
        assertEquals(new BigInteger("1000000"), loaded.supply("copper").reserve());
        assertEquals(BigInteger.valueOf(5), loaded.balance(player, "note"));
        assertEquals(BigInteger.valueOf(5), loaded.centralM1());
        assertEquals(BigInteger.valueOf(5), loaded.supply("note").liability());
        loaded.checkInvariants();
    }

    @Test
    void migrationDoesNotDropBalancesWhenTheMixedReserveIsBelowDeposits() {
        BankBooks modern = BankBooks.create(BankConfig.defaults());
        modern.deposit(player, "diamond", BigInteger.TEN);
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(modern.toJson()).getAsJsonObject();
        com.google.gson.JsonObject diamond = root.getAsJsonObject("supplies").getAsJsonObject("diamond");
        diamond.remove("depositVault");
        diamond.addProperty("reserve", "3");
        diamond.addProperty("locked", "0");
        diamond.addProperty("liability", "1010");

        BankBooks loaded = BankBooks.fromJson(root.toString(), BankConfig.defaults());

        assertEquals(BigInteger.TEN, loaded.balance(player, "diamond"));
        assertEquals(BigInteger.TEN, loaded.supply("diamond").depositVault());
        assertEquals(BigInteger.ZERO, loaded.supply("diamond").reserve());
        loaded.checkInvariants();
    }

    @Test
    void amountsParseSuffixesAndScientificNotation() {
        assertEquals(new BigInteger("1500"), Amounts.parse("1.5k"));
        assertEquals(new BigInteger("1000"), Amounts.parse("1_000"));
        assertEquals(new BigInteger("2000000"), Amounts.parse("2m"));
        assertEquals(new BigInteger("1000000"), Amounts.parse("1_000_000"));
        assertEquals(BigInteger.TEN.pow(40), Amounts.parse("1e40"));
    }
}
