package dev.jdguo.bank.ledger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterestTest {
    private final UUID saver = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
    private final UUID other = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");
    private final UUID payer = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3");

    @Test
    void personalNotesDefaultToZeroWhileCentralNotesStayAtTwoPercent() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertEquals(0, new BigDecimal("0.02").compareTo(books.interestFraction()));
        assertEquals(0, BigDecimal.ZERO.compareTo(books.personalInterestFraction()));
        books.setPersonalInterestFraction(new BigDecimal("0.05"));
        assertEquals(0, new BigDecimal("0.02").compareTo(books.interestFraction()));
        assertEquals(0, new BigDecimal("0.05").compareTo(books.personalInterestFraction()));

        BankBooks loaded = BankBooks.fromJson(books.toJson(), BankConfig.defaults());
        assertEquals(0, new BigDecimal("0.05").compareTo(loaded.personalInterestFraction()));

        BankBooks fresh = BankBooks.create(BankConfig.defaults());
        fresh.setAlpha("note", BigDecimal.ZERO);
        assertTrue(fresh.createPersonalNote(saver, "泉", false, BigInteger.ONE).success());
        fresh.setPersonalReserve(saver, "diamond", new BigInteger("400"));
        assertTrue(fresh.issuePersonalNote(saver, new BigInteger("400")).success());
        fresh.setFeeFraction(new BigDecimal("0.50"));
        fresh.creditUnbacked(payer, "note", new BigInteger("200"));
        assertTrue(fresh.buyPersonalNote(payer, "泉", new BigInteger("100")).success());
        var series = fresh.personalNote(saver);
        BigInteger held = fresh.personalHolding(series.id(), saver);
        BigInteger pool = series.interestPool();
        assertTrue(pool.signum() > 0);
        fresh.accrueInterest(0);
        fresh.accrueInterest(1);
        assertEquals(held, fresh.personalHolding(series.id(), saver));
        assertEquals(pool, series.interestPool());
        fresh.checkInvariants();
    }

    @Test
    void commoditySavingsEarnNothing() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(saver, "diamond", new BigInteger("36500"));
        books.deposit(saver, "copper", new BigInteger("36500"));
        books.accrueInterest(0);
        books.accrueInterest(10);

        assertEquals(new BigInteger("36500"), books.balance(saver, "diamond"));
        assertEquals(new BigInteger("36500"), books.balance(saver, "copper"));
        assertEquals(BigInteger.ZERO, books.balance(saver, "note"));
        assertEquals(BigInteger.ZERO, books.centralM1());
        books.checkInvariants();
    }

    @Test
    void paperCountsInM1ButOnlyBookEntryEarnsInterest() {
        BankBooks books = fund(new BigInteger("20"));
        books.creditUnbacked(saver, "note", new BigInteger("73000"));
        assertTrue(books.issueCentralPaper(saver, new BigInteger("36500")).result().success());
        BigInteger issuance = books.supply("diamond").reserve();
        books.accrueInterest(0);
        BankBooks.InterestDay day = books.accrueInterest(1);

        assertEquals(new BigInteger("36502"), books.balance(saver, "note"));
        assertEquals(new BigInteger("36500"), books.centralM0());
        assertEquals(new BigInteger("73002"), books.centralM1());
        assertEquals(new BigInteger("2"), day.credits().get(0).amount());
        assertEquals(issuance, books.supply("diamond").reserve());
        assertEquals(new BigInteger("18"), books.interestPool("note"));
        books.checkInvariants();
    }

    @Test
    void oneGameDayPaysBalanceTimesRateOver365() {
        BankBooks books = fund(new BigInteger("20"));
        books.creditUnbacked(saver, "note", new BigInteger("36500"));
        books.accrueInterest(3);
        BankBooks.InterestDay day = books.accrueInterest(4);

        assertEquals(1, day.credits().size());
        assertEquals(new BigInteger("2"), day.credits().get(0).amount());
        assertEquals(new BigInteger("36502"), books.balance(saver, "note"));
        assertEquals(new BigInteger("36502"), books.centralDeposits());
        books.checkInvariants();
    }

    @Test
    void aShortPoolPaysProRataAndDoesNotMintTheRest() {
        BankBooks books = fund(new BigInteger("3"));
        books.creditUnbacked(saver, "note", new BigInteger("36500"));
        books.creditUnbacked(other, "note", new BigInteger("36500"));
        BigInteger m1 = books.centralM1();
        BigInteger diamondIssuance = books.supply("diamond").reserve();
        books.accrueInterest(0);
        books.accrueInterest(1);

        assertEquals(new BigInteger("36501"), books.balance(saver, "note"));
        assertEquals(new BigInteger("36501"), books.balance(other, "note"));
        assertEquals(m1.add(BigInteger.TWO), books.centralM1());
        assertEquals(BigInteger.ONE, books.interestPool("note"));
        assertEquals(diamondIssuance, books.supply("diamond").reserve());
        books.checkInvariants();
    }

    @Test
    void exchangeFeeFundsThePoolInsteadOfDrainingItAway() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        BigInteger m1 = new BigInteger("51");
        books.setAlpha("note", BigDecimal.ZERO);
        books.creditUnbacked(payer, "note", m1);
        BigInteger diamonds = books.supply("diamond").reserve();

        TxResult bought = books.exchange(payer, "note", "diamond", new BigInteger("50"));

        assertTrue(bought.success(), bought.code());
        assertEquals(new BigInteger("51"), bought.amount());
        assertEquals(BigInteger.ONE, books.interestPool("note"));
        assertEquals(BigInteger.ZERO, books.balance(payer, "note"));
        assertEquals(BigInteger.ZERO, books.centralM1());
        assertEquals(diamonds.subtract(new BigInteger("50")), books.supply("diamond").reserve());

        BankBooks copperBooks = BankBooks.create(BankConfig.defaults());
        copperBooks.deposit(payer, "copper", new BigInteger("5000"));
        TxResult copper = copperBooks.exchange(payer, "copper", "diamond", BigInteger.ONE);
        assertTrue(copper.success(), copper.code());
        assertEquals(new BigInteger("20"), copperBooks.interestPool("copper"));
        assertEquals(new BigInteger("1001000"), copperBooks.supply("copper").reserve());
        copperBooks.checkInvariants();
        books.checkInvariants();
    }

    @Test
    void personalBookEntryEarnsWhilePaperAndCommodityReservesDoNot() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setAlpha("note", BigDecimal.ZERO);
        assertTrue(books.createPersonalNote(saver, "泉", false, BigInteger.ONE).success());
        books.setPersonalReserve(saver, "diamond", new BigInteger("1000"));
        assertTrue(books.issuePersonalNote(saver, new BigInteger("1000")).success());
        books.setFeeFraction(new BigDecimal("0.50"));
        books.creditUnbacked(payer, "note", new BigInteger("1000"));
        assertTrue(books.buyPersonalNote(payer, "泉", new BigInteger("400")).success());
        var series = books.personalNote(saver);
        assertEquals(new BigInteger("200"), series.interestPool());
        assertEquals(new BigInteger("1000"), books.personalReserve(saver, "diamond"));
        assertEquals(new BigInteger("1000"), books.supply("diamond").reserve());
        assertTrue(books.issuePersonalPaper(payer, series.id(), new BigInteger("400")).result().success());
        books.setPersonalInterestFraction(BigDecimal.ONE);
        books.accrueInterest(0);
        books.accrueInterest(1);

        assertEquals(new BigInteger("401"), books.personalHolding(series.id(), saver));
        assertEquals(BigInteger.ZERO, books.personalHolding(series.id(), payer));
        assertEquals(new BigInteger("400"), books.personalM0(series));
        assertEquals(new BigInteger("801"), series.circulation());
        assertEquals(new BigInteger("199"), series.interestPool());
        assertEquals(new BigInteger("1000"), books.personalReserve(saver, "diamond"));
        assertEquals(new BigInteger("1000"), books.supply("diamond").reserve());
        books.checkInvariants();
    }

    @Test
    void oldPersonalNoteGainsAnEmptyInterestPoolWithoutLosingHoldings() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertTrue(books.createPersonalNote(saver, "泉", false, BigInteger.ONE).success());
        books.setPersonalReserve(saver, "diamond", BigInteger.TEN);
        assertTrue(books.issuePersonalNote(saver, BigInteger.TEN).success());
        JsonObject root = JsonParser.parseString(books.toJson()).getAsJsonObject();
        root.getAsJsonArray("personalNotes").get(0).getAsJsonObject().remove("interestPool");
        root.getAsJsonArray("personalNotes").get(0).getAsJsonObject().remove("interestDay");

        BankBooks loaded = BankBooks.fromJson(root.toString(), BankConfig.defaults());
        var series = loaded.personalNote(saver);
        assertEquals(BigInteger.TEN, loaded.personalHolding(series.id(), saver));
        assertEquals(BigInteger.ZERO, series.interestPool());
        assertEquals(-1L, series.interestDay());
        loaded.checkInvariants();
    }

    @Test
    void oldLedgerKeepsBalancesAndStartsWithAnEmptyPool() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.deposit(saver, "diamond", BigInteger.TEN);
        books.creditUnbacked(saver, "note", new BigInteger("40"));
        JsonObject root = JsonParser.parseString(books.toJson()).getAsJsonObject();
        root.remove("interestRate");
        root.remove("interestPool");
        root.remove("interestDust");
        root.remove("lastInterestDay");

        BankBooks loaded = BankBooks.fromJson(root.toString(), BankConfig.defaults());

        assertEquals(BigInteger.TEN, loaded.balance(saver, "diamond"));
        assertEquals(new BigInteger("40"), loaded.balance(saver, "note"));
        assertEquals(BigInteger.ZERO, loaded.interestPool("note"));
        assertEquals(-1L, loaded.lastInterestDay());
        assertEquals(0, new java.math.BigDecimal("0.02").compareTo(loaded.interestFraction()));
        assertEquals(0, BigDecimal.ZERO.compareTo(loaded.personalInterestFraction()));
        loaded.accrueInterest(8);
        assertEquals(new BigInteger("40"), loaded.balance(saver, "note"));
        loaded.checkInvariants();
    }

    private BankBooks fund(BigInteger feeNotes) {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setAlpha("note", BigDecimal.ZERO);
        BigInteger diamonds = feeNotes.multiply(new BigInteger("50"));
        BigInteger cost = diamonds.add(feeNotes);
        books.creditUnbacked(payer, "note", cost);
        TxResult result = books.exchange(payer, "note", "diamond", diamonds);
        assertTrue(result.success(), result.code());
        assertEquals(feeNotes, books.interestPool("note"));
        assertEquals(BigInteger.ZERO, books.balance(payer, "note"));
        return books;
    }
}
