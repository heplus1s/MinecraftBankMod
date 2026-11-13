package dev.jdguo.bank.text;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankPhrasesTest {
    @Test
    void freshBooksOpenAtOneDiamondPerThousandCopper() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertEquals(new BigInteger("1000000"), books.supply("copper").reserve());
        assertEquals(new BigInteger("1000000"), books.supply("copper").liability());
        assertEquals(new BigInteger("1000"), books.supply("diamond").reserve());
        assertEquals(new BigInteger("1000"), books.supply("diamond").liability());
        assertEquals(0, new BigDecimal("1000").compareTo(books.unitsPer("copper", "diamond")));
        assertEquals("1000", BankPhrases.copperPerDiamond(books));
        assertEquals("银行券", books.currency("note").name());
        assertEquals("bank note", books.currency("note").nameEn());
        assertEquals(0, BigDecimal.ONE.compareTo(books.currency("note").parPerBacking()));
        assertEquals(0, BigDecimal.ONE.compareTo(books.unitsPer("note", "diamond")));
        assertEquals(new BigInteger("1000"), books.supply("note").reserve());
    }

    @Test
    void openingReserveMovesAnchorLiabilityAndTheQuote() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID player = UUID.randomUUID();
        books.deposit(player, "diamond", BigInteger.TEN);
        books.setOpeningReserve("diamond", new BigInteger("2000"));
        assertEquals(new BigInteger("2000"), books.supply("diamond").anchor());
        assertEquals(new BigInteger("2010"), books.supply("diamond").reserve());
        assertEquals(new BigInteger("2010"), books.supply("diamond").liability());
        assertEquals(BigInteger.TEN, books.balance(player, "diamond"));
        assertEquals(0, new BigDecimal("1000000").divide(new BigDecimal("2010"), dev.jdguo.bank.fx.FxMath.MC)
                .compareTo(books.unitsPer("copper", "diamond")));
        books.checkInvariants();
    }

    @Test
    void personalReservesStayOutOfSavingsAndSurviveAReload() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID player = UUID.randomUUID();
        assertEquals(BigInteger.ZERO, books.personalReserve(player, "diamond"));
        books.setPersonalReserve(player, "diamond", BigInteger.TEN);
        books.deposit(player, "copper", BigInteger.valueOf(64));
        assertEquals(BigInteger.TEN, books.personalReserve(player, "diamond"));
        assertEquals(BigInteger.ZERO, books.balance(player, "diamond"));
        assertEquals(new BigInteger("1000"), books.supply("diamond").reserve());

        BankBooks loaded = BankBooks.fromJson(books.toJson(), BankConfig.defaults());
        assertEquals(BigInteger.TEN, loaded.personalReserve(player, "diamond"));
        assertEquals(BigInteger.valueOf(64), loaded.balance(player, "copper"));
        assertEquals(BigInteger.ZERO, loaded.balance(player, "diamond"));
        assertEquals(new BigInteger("1000064"), loaded.supply("copper").reserve());
        assertEquals(new BigInteger("1000"), loaded.supply("diamond").reserve());
        loaded.checkInvariants();
    }

    @Test
    void depositWithdrawAndExchangeUseDatapackChat() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID player = UUID.randomUUID();
        books.deposit(player, "copper", BigInteger.valueOf(64));
        Spoken deposit = BankPhrases.deposit(books, player, "copper", BigInteger.valueOf(64));
        assertEquals("[银行] 存入 铜锭 ×64 成功 ｜余额：铜 64，钻 0，银行券 0", deposit.zh());
        assertEquals("[Bank] Deposited copper ×64 | Balance: Cu 64, Dia 0, bank note 0", deposit.en());
        assertEquals(Tone.GOLD, deposit.tone());
        assertFalse(deposit.zh().contains("银票"));

        books.deposit(player, "diamond", BigInteger.ONE);
        Spoken diamond = BankPhrases.deposit(books, player, "diamond", BigInteger.ONE);
        assertEquals(Tone.AQUA, diamond.tone());
        assertTrue(diamond.zh().startsWith("[银行] 存入 钻石 ×1 成功"));

        books.withdraw(player, "copper", BigInteger.TEN, false);
        Spoken withdraw = BankPhrases.withdraw(books, player, "copper", BigInteger.TEN);
        assertEquals("[银行] 取出 铜×10 ｜余额：铜 54，钻 1，银行券 0", withdraw.zh());
        assertEquals(Tone.GOLD, withdraw.tone());

        Spoken bought = BankPhrases.exchange(books, player, "copper", "diamond", BigInteger.ONE);
        assertEquals(Tone.GREEN, bought.tone());
        assertTrue(bought.zh().startsWith("[银行] 买入 钻×1 成功 ｜余额："));
        assertTrue(bought.en().startsWith("[Bank] Bought diamond ×1 | Balance:"));

        Spoken sold = BankPhrases.exchange(books, player, "diamond", "copper", BigInteger.TEN);
        assertEquals(Tone.DARK_RED, sold.tone());
        assertTrue(sold.zh().startsWith("[银行] 卖出 钻×10 成功"));

        assertEquals("[银行] 你的账户余额不足。", BankPhrases.failure(BankPhrases.Kind.WITHDRAW, "insufficient_funds", "copper", null).zh());
        assertEquals("[银行] 银行准备金不足。", BankPhrases.failure(BankPhrases.Kind.WITHDRAW, "insufficient_reserves", "diamond", null).zh());
        assertEquals("[银行] 铜锭不足。", BankPhrases.failure(BankPhrases.Kind.EXCHANGE, "insufficient_funds", "diamond", "copper").zh());
        assertEquals("[银行] 你的钻石不足。", BankPhrases.failure(BankPhrases.Kind.EXCHANGE, "insufficient_funds", "copper", "diamond").zh());
        assertEquals("[Bank] Not enough copper.", BankPhrases.failure(BankPhrases.Kind.EXCHANGE, "insufficient_funds", "diamond", "copper").en());
    }

    @Test
    void markerIdRoundTripsWithTheOutlet() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        UUID marker = UUID.randomUUID();
        books.addOutlet(new dev.jdguo.bank.outlet.OutletPoint(
                "lobby", "minecraft:overworld", 3, 64, 5, 4.0, 2.4, true, null, marker));
        BankBooks loaded = BankBooks.fromJson(books.toJson(), BankConfig.defaults());
        assertEquals(marker, loaded.outlet("lobby").markerId());
        assertEquals(loaded.outlet("lobby"), loaded.outletByMarker(marker));
    }
}
