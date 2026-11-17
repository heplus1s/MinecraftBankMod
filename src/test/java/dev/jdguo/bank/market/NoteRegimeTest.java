package dev.jdguo.bank.market;

import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteRegimeTest {
    private final UUID issuerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID issuerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID buyer = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void floatingBackingQuoteDoesNotMoveFromOwnershipTransfers() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        PersonalNote pay = open(books, issuerA, "Aa", NoteRegime.FLOAT, "diamond", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        PersonalNote receive = open(books, issuerB, "Bb", NoteRegime.FLOAT, "diamond", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        books.creditHeld(buyer, "note", new BigInteger("2000"));
        BigDecimal beforeA = books.personalDiamondPrice(pay);
        BigDecimal beforeB = books.personalDiamondPrice(receive);

        SwapTrade swap = books.swapPersonalNotes(issuerA, "Aa", "Bb", BigInteger.TEN);

        assertTrue(swap.success(), swap.code());
        assertEquals(0, books.personalDiamondPrice(receive).compareTo(beforeB));
        assertEquals(0, books.personalDiamondPrice(pay).compareTo(beforeA));
        assertEquals(BigInteger.TEN, books.personalReserve(issuerB, "diamond"));
        books.checkInvariants();

        BankBooks later = BankBooks.create(BankConfig.defaults());
        PersonalNote traded = open(later, issuerB, "Bb", NoteRegime.FLOAT, "diamond", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        later.deposit(buyer, "copper", new BigInteger("20000"));
        later.creditHeld(buyer, "note", new BigInteger("100"));
        var bought = later.buyPersonalNote(buyer, "Bb", BigInteger.TEN);
        assertTrue(bought.success(), bought.code());
        BigDecimal market = later.personalDiamondPrice(traded).multiply(BigDecimal.TEN);
        assertEquals("route_to_savings", later.redeemPersonal(buyer, "Bb", BigInteger.TEN, "diamond").code());
        assertTrue(later.redeemPersonalToSavings(buyer, traded.id(), BigDecimal.TEN, "float-redeem").success());
        assertEquals(0, later.balanceExact(buyer, "diamond").compareTo(market));
        assertEquals(0, later.personalActiveExact(traded).signum());
        assertEquals(0, later.personalRecovered(traded.id()).compareTo(BigDecimal.TEN));
        later.checkInvariants();
    }

    @Test
    void pegTargetDoesNotOverwriteActualBackingSettlement() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        PersonalNote peg = open(books, issuerB, "Bb", NoteRegime.PEG, "diamond", new BigInteger("2"), new BigInteger("20"), BigInteger.TEN);
        PersonalNote floating = open(books, issuerA, "Aa", NoteRegime.FLOAT, "diamond", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        assertEquals(0, new BigDecimal("2").compareTo(books.personalDiamondPrice(peg)));

        books.deposit(buyer, "copper", new BigInteger("20000"));
        books.creditHeld(buyer, "note", new BigInteger("100"));
        assertTrue(books.buyPersonalNote(buyer, "Bb", BigInteger.valueOf(5)).success());
        assertEquals(0, new BigDecimal("2").compareTo(books.personalDiamondPrice(peg)));
        assertEquals(new BigInteger("16"), peg.locked());
        assertEquals(new BigInteger("20"), books.personalReserve(issuerB, "diamond"));

        SwapTrade swap = books.swapPersonalNotes(issuerA, "Aa", "Bb", BigInteger.ONE);
        assertTrue(swap.success(), swap.code());
        assertEquals(new BigInteger("2"), swap.paidNotes());
        assertEquals(0, new BigDecimal("2").compareTo(books.personalDiamondPrice(peg)));
        assertEquals(0, books.personalDiamondPrice(floating).compareTo(BigDecimal.ONE));

        books.setPersonalReserve(issuerB, "diamond", new BigInteger("6"));
        assertEquals("route_to_savings", books.redeemPersonal(buyer, "Bb", BigInteger.valueOf(5), "diamond").code());
        assertTrue(books.redeemPersonalToSavings(buyer, peg.id(), new BigDecimal("5"), "peg-redeem").success());
        assertEquals(0, books.balanceExact(buyer, "diamond").compareTo(new BigDecimal("3")));
        assertEquals(0, books.personalActiveExact(peg).compareTo(new BigDecimal("5")));
        assertEquals(0, books.personalDiamondPrice(peg).compareTo(new BigDecimal("0.6")));
        assertEquals(new BigInteger("3"), books.personalReserve(issuerB, "diamond"));
        books.checkInvariants();
    }

    @Test
    void hardTargetStaysConfiguredWhileActualSettlementCanFall() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        open(books, issuerA, "Aa", NoteRegime.FLOAT, "diamond", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        books.setPersonalNoteCoverage(new BigDecimal("0.5"));
        PersonalNote hard = open(books, issuerB, "Bb", NoteRegime.HARD, "diamond", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        assertEquals(BigInteger.TEN, hard.locked());
        assertEquals("reserve", books.issuePersonalNote(issuerB, BigInteger.ONE).code());

        books.deposit(buyer, "copper", new BigInteger("20000"));
        books.creditHeld(buyer, "note", new BigInteger("100"));
        assertTrue(books.buyPersonalNote(buyer, "Bb", BigInteger.TEN).success());
        assertEquals(0, BigDecimal.ONE.compareTo(books.personalDiamondPrice(hard)));

        // Secondary holders must explicitly offer inventory before it can be taken.
        assertTrue(books.listPersonal(buyer, "Bb", BigInteger.ONE).success());
        SwapTrade swap = books.swapPersonalNotes(issuerA, "Aa", "Bb", BigInteger.ONE);
        assertTrue(swap.success(), swap.code());
        assertEquals(BigInteger.ONE, swap.paidNotes());
        assertEquals(0, BigDecimal.ONE.compareTo(books.personalDiamondPrice(hard)));

        books.deposit(issuerB, "copper", new BigInteger("5000"));
        BigInteger[] face = cash(books, BigDecimal.ONE);
        BigInteger[] premium = cash(books, new BigDecimal("1.1"));
        NoteTrade sold = books.sellPersonalNote(buyer, "Bb", BigInteger.ONE);
        assertTrue(sold.success(), sold.code());
        assertEquals(face[0], sold.centralPaid());
        assertEquals(face[1], sold.copperPaid());
        assertTrue(face[0].compareTo(premium[0]) != 0 || face[1].compareTo(premium[1]) != 0);

        books.setPersonalReserve(issuerB, "diamond", new BigInteger("4"));
        assertEquals("route_to_savings", books.redeemPersonal(buyer, "Bb", BigInteger.valueOf(8), "diamond").code());
        assertTrue(books.redeemPersonalToSavings(buyer, hard.id(), new BigDecimal("8"), "hard-redeem").success());
        assertEquals(0, books.balanceExact(buyer, "diamond").compareTo(new BigDecimal("3.2")));
        assertEquals(0, books.personalActiveExact(hard).compareTo(new BigDecimal("2")));
        assertEquals(0, books.personalDiamondPrice(hard).compareTo(new BigDecimal("0.4")));
        assertEquals(NoteRegime.HARD, hard.regime());
        books.checkInvariants();
    }

    @Test
    void savedNotesWithoutARegimeStayFloating() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        open(books, issuerA, "Aa", NoteRegime.FLOAT, "diamond", BigInteger.ONE, BigInteger.ONE, BigInteger.ONE);
        var root = JsonParser.parseString(books.toJson()).getAsJsonObject();
        var saved = root.getAsJsonArray("personalNotes").get(0).getAsJsonObject();
        saved.remove("regime");
        saved.remove("anchor");
        BankBooks loaded = BankBooks.fromJson(root.toString(), BankConfig.defaults());
        PersonalNote note = loaded.personalNote(issuerA);
        assertEquals(NoteRegime.FLOAT, note.regime());
        assertEquals("diamond", note.anchor());
        assertEquals(BigInteger.ONE, note.circulation());
        loaded.checkInvariants();
    }

    private static BigInteger[] cash(BankBooks books, BigDecimal diamondEach) {
        BigDecimal exact = dev.jdguo.bank.ledger.Money.of(diamondEach.multiply(books.unitsPer("note", "diamond"), dev.jdguo.bank.fx.FxMath.MC));
        BigInteger central = exact.setScale(0, java.math.RoundingMode.DOWN).toBigInteger();
        BigInteger copper = exact.subtract(new BigDecimal(central))
                .multiply(new BigDecimal(books.copperPerDiamond()), dev.jdguo.bank.fx.FxMath.MC)
                .setScale(0, java.math.RoundingMode.HALF_UP).toBigInteger();
        if (copper.signum() < 0) {
            copper = BigInteger.ZERO;
        }
        return new BigInteger[] {central, copper};
    }

    private static PersonalNote open(BankBooks books, UUID issuer, String name, NoteRegime regime, String anchor,
                                     BigInteger par, BigInteger reserve, BigInteger amount) {
        books.setPersonalReserve(issuer, anchor, reserve);
        assertTrue(books.createPersonalNote(issuer, name, regime, anchor, par).success());
        BigInteger cover = amount.multiply(par.max(BigInteger.ONE));
        books.creditHeld(issuer, "note", cover);
        assertTrue(books.depositCentralReserve(issuer, cover).success());
        assertTrue(books.issuePersonalNote(issuer, amount).success());
        return books.personalNote(issuer);
    }
}
