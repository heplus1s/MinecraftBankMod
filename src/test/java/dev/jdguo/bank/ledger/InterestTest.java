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
    void bookEntryNotesEarnAndPaperDoesNot() {
        BankBooks books = fund(new BigInteger("20"));
        books.creditUnbacked(saver, "note", new BigInteger("73000"));
        assertTrue(books.issueCentralPaper(saver, new BigInteger("36500")).result().success());
        BigInteger issuance = books.supply("diamond").reserve();
        books.accrueInterest(0);
        BankBooks.InterestDay day = books.accrueInterest(1);

        assertEquals(new BigInteger("36502"), books.balance(saver, "note"));
        assertEquals(new BigInteger("36500"), books.centralM0());
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
