package dev.jdguo.bank.market;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalMarketTest {
    private final UUID issuer = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID buyer = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void namesAreAtMostThreeLettersOrHanAndMustBeUnique() {
        assertEquals("Ste", PersonalMarket.shortName("Steve"));
        assertEquals("泉号", PersonalMarket.shortName("泉12号"));
        assertTrue(PersonalMarket.validName("泉"));
        assertTrue(PersonalMarket.validName("AbC"));
        assertFalse(PersonalMarket.validName("ABCD"));
        assertFalse(PersonalMarket.validName("四个汉字"));
        assertFalse(PersonalMarket.validName("A1"));
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertTrue(books.createPersonalNote(issuer, "泉", false, BigInteger.ONE).success());
        assertEquals("taken", books.createPersonalNote(buyer, "泉", false, BigInteger.ONE).code());
        assertEquals("exists", books.createPersonalNote(issuer, "Ab", false, BigInteger.ONE).code());
        assertEquals("name", books.createPersonalNote(buyer, "太长的名字", false, BigInteger.ONE).code());
    }

    @Test
    void unbackedIssueDoesNotLockAReserve() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.createPersonalNote(issuer, "泉", false, BigInteger.ONE);
        BigInteger diamonds = books.supply("diamond").reserve();

        assertEquals("reserve", books.issuePersonalNote(issuer, BigInteger.TEN).code());
        assertTrue(books.issuePersonalNoteUnbacked(issuer, BigInteger.TEN).success());

        PersonalNote note = books.personalNote(issuer);
        assertEquals(BigInteger.TEN, note.circulation());
        assertEquals(BigInteger.ZERO, note.locked());
        assertEquals(BigInteger.TEN, books.personalHolding(note.id(), issuer));
        assertEquals(BigInteger.ZERO, books.personalReserve(issuer, "diamond"));
        assertEquals(diamonds, books.supply("diamond").reserve());
        books.checkInvariants();
    }

    @Test
    void issueNeedsPersonalReservesAndOpensAtOneDiamond() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        assertEquals(0, BigDecimal.ONE.compareTo(books.unitsPer("note", "diamond")));
        books.createPersonalNote(issuer, "泉", false, BigInteger.ONE);
        assertEquals("reserve", books.issuePersonalNote(issuer, BigInteger.TEN).code());
        books.setPersonalReserve(issuer, "diamond", BigInteger.TEN);
        assertTrue(books.issuePersonalNote(issuer, BigInteger.TEN).success());
        assertEquals(BigInteger.TEN, books.personalHolding(books.personalNote(issuer).id(), issuer));
        assertEquals(BigInteger.ZERO, books.balance(issuer, "diamond"));
        assertEquals(new BigInteger("1000"), books.supply("diamond").reserve());
        assertEquals(0, BigDecimal.ONE.compareTo(books.personalDiamondPrice(books.personalNote(issuer))));
    }

    @Test
    void buyingAgainstCentralNotesMovesThePriceUp() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.createPersonalNote(issuer, "泉", false, BigInteger.ONE);
        books.setPersonalReserve(issuer, "diamond", BigInteger.TEN);
        books.issuePersonalNote(issuer, BigInteger.TEN);
        books.creditUnbacked(buyer, "note", BigInteger.TEN);
        books.deposit(buyer, "copper", new BigInteger("100"));
        BigDecimal before = books.personalDiamondPrice(books.personalNote(issuer));
        NoteTrade bought = books.buyPersonalNote(buyer, "泉", BigInteger.ONE);
        assertTrue(bought.success(), bought.code());
        assertEquals(BigInteger.ONE, books.personalHolding(books.personalNote(issuer).id(), buyer));
        assertEquals(new BigInteger("9"), books.personalHolding(books.personalNote(issuer).id(), issuer));
        assertEquals(BigInteger.TEN.subtract(bought.centralPaid()), books.balance(buyer, "note"));
        assertEquals(bought.centralPaid(), books.balance(issuer, "note"));
        assertTrue(books.personalDiamondPrice(books.personalNote(issuer)).compareTo(before) > 0);
        books.checkInvariants();
    }

    @Test
    void copperParUsesTheOpeningThousandCopperDiamondRate() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.createPersonalNote(issuer, "Cu", true, new BigInteger("1000"));
        books.setPersonalReserve(issuer, "copper", new BigInteger("1000"));
        assertTrue(books.issuePersonalNote(issuer, BigInteger.ONE).success());
        assertEquals(0, BigDecimal.ONE.compareTo(books.personalDiamondPrice(books.personalNote(issuer))));
    }

    @Test
    void personalNotesRoundTripSeparatelyFromSavings() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.createPersonalNote(issuer, "泉", false, BigInteger.ONE);
        books.setPersonalReserve(issuer, "diamond", BigInteger.ONE);
        books.issuePersonalNote(issuer, BigInteger.ONE);
        BankBooks loaded = BankBooks.fromJson(books.toJson(), BankConfig.defaults());
        assertEquals("泉", loaded.personalNote(issuer).name());
        assertEquals(BigInteger.ONE, loaded.personalHolding(loaded.personalNote(issuer).id(), issuer));
        assertEquals(BigInteger.ZERO, loaded.balance(issuer, "note"));
        assertEquals(new BigInteger("1000"), loaded.supply("diamond").reserve());
        assertEquals(new BigInteger("1000000"), loaded.supply("copper").reserve());
    }
}
