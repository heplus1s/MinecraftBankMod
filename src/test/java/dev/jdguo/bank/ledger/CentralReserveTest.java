package dev.jdguo.bank.ledger;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.market.NoteRegime;
import dev.jdguo.bank.market.PersonalNote;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CentralReserveTest {
    private final UUID issuer = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");

    @Test
    void depositMovesSavingsIntoTheReserveAccountWithoutChangingM1OrIssuance() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.createPersonalNote(issuer, "泉", false, BigInteger.ONE);
        books.creditUnbacked(issuer, "note", BigInteger.TEN);
        BigInteger m1 = books.centralM1();
        BigInteger issuance = books.supply("diamond").reserve();
        BigInteger pool = books.interestPool("note");

        assertTrue(books.depositCentralReserve(issuer, BigInteger.TEN).success());

        assertEquals(BigInteger.ZERO, books.balance(issuer, "note"));
        assertEquals(BigInteger.TEN, books.centralReserve(issuer));
        assertEquals(m1.subtract(BigInteger.TEN), books.centralM1());
        assertEquals(books.centralM0().add(books.centralDeposits()), books.centralM1());
        assertEquals(issuance, books.supply("diamond").reserve());
        assertEquals(pool, books.interestPool("note"));
        books.checkInvariants();
    }

    @Test
    void floatIssuancePausesBelowTenPercentAndRedemptionStillPays() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setAlpha("note", java.math.BigDecimal.ZERO);
        books.setPersonalReserve(issuer, "diamond", new BigInteger("100"));
        books.createPersonalNote(issuer, "泉", false, BigInteger.ONE);
        books.creditUnbacked(issuer, "note", BigInteger.ONE);
        assertTrue(books.depositCentralReserve(issuer, BigInteger.ONE).success());
        assertTrue(books.issuePersonalNote(issuer, BigInteger.TEN).success());
        assertTrue(books.issuePersonalNote(issuer, BigInteger.ONE).success());
        assertTrue(books.issuePersonalNoteUnbacked(issuer, BigInteger.ONE).success());

        var buyer = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");
        books.deposit(buyer, "copper", new BigInteger("5000"));
        books.creditUnbacked(buyer, "note", new BigInteger("50"));
        var bought = books.buyPersonalNote(buyer, "泉", BigInteger.TEN);
        assertTrue(bought.success(), bought.code());
        var redeemed = books.redeemPersonal(buyer, "泉", BigInteger.TEN, "diamond");
        assertTrue(redeemed.success(), redeemed.code());
        assertEquals(BigInteger.ONE, books.centralReserve(issuer));
        books.checkInvariants();
    }

    @Test
    void reserveNotesEarnNoInterestAndAShortfallIsNotFilledFromTheSavingsPool() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setAlpha("note", java.math.BigDecimal.ZERO);
        books.setInterestFraction(java.math.BigDecimal.ONE);
        books.createPersonalNote(issuer, "泉", false, BigInteger.ONE);
        books.creditUnbacked(issuer, "note", new BigInteger("365"));
        assertTrue(books.depositCentralReserve(issuer, new BigInteger("365")).success());
        books.creditUnbacked(issuer, "note", new BigInteger("367"));
        assertTrue(books.exchange(issuer, "note", "diamond", BigInteger.ONE).success());
        java.math.BigDecimal pool = books.interestPoolExact("note");
        assertTrue(pool.signum() > 0);
        books.accrueInterest(0);
        books.accrueInterest(1);

        assertEquals(new BigInteger("365"), books.centralReserve(issuer));
        assertEquals(0, new java.math.BigDecimal("366.0000").compareTo(books.balanceExact(issuer, "note")));
        assertTrue(books.interestPoolExact("note").compareTo(pool) < 0);
        java.math.BigDecimal untouched = books.interestPoolExact("note");
        assertTrue(books.issuePersonalNoteUnbacked(issuer, new BigInteger("4000")).success());
        assertEquals(new BigInteger("365"), books.centralReserve(issuer));
        assertEquals(0, untouched.compareTo(books.interestPoolExact("note")));
        assertEquals(new BigInteger("1000").subtract(BigInteger.ONE), books.supply("diamond").reserve());
        books.checkInvariants();
    }

    @Test
    void pegKeepsTwentyPercentInNotesAndTheRestLocked() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setAlpha("note", java.math.BigDecimal.ZERO);
        books.setPersonalReserve(issuer, "diamond", new BigInteger("20"));
        assertTrue(books.createPersonalNote(issuer, "Bb", NoteRegime.PEG, "diamond", new BigInteger("2")).success());
        books.creditUnbacked(issuer, "note", new BigInteger("4"));
        assertTrue(books.depositCentralReserve(issuer, new BigInteger("4")).success());
        assertTrue(books.issuePersonalNote(issuer, BigInteger.TEN).success());

        PersonalNote note = books.personalNote(issuer);
        assertEquals(new BigInteger("16"), note.locked());
        assertEquals(BigInteger.ZERO, books.requiredCentralReserve(note));
        assertEquals(new BigInteger("1000"), books.supply("diamond").reserve());
        books.checkInvariants();
    }

    @Test
    void hardKeepsFullCommodityLockPlusAFivePercentBuffer() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.setAlpha("note", java.math.BigDecimal.ZERO);
        books.setPersonalReserve(issuer, "diamond", BigInteger.TEN);
        assertTrue(books.createPersonalNote(issuer, "Hd", NoteRegime.HARD, "diamond", BigInteger.ONE).success());
        assertTrue(books.issuePersonalNote(issuer, BigInteger.TEN).success());
        assertEquals(BigInteger.TEN, books.personalNote(issuer).locked());
        assertEquals(BigInteger.ZERO, books.requiredCentralReserve(books.personalNote(issuer)));
        books.checkInvariants();
    }
}
