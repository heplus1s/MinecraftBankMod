package dev.jdguo.bank.market;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.instruments.NotePaper;
import dev.jdguo.bank.instruments.NoteSeries;
import dev.jdguo.bank.instruments.PresentedNote;
import dev.jdguo.bank.fx.FxMath;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.TxResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecondaryMarketTest {
    private final UUID issuerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID issuerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID buyer = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void crossRateSwapPaysTheResidualInCentralNotesAndDoesNotMoveReserves() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        PersonalNote expensive = issue(books, issuerA, "Aa", new BigInteger("2"), new BigInteger("20"), BigInteger.TEN);
        PersonalNote cheap = issue(books, issuerB, "Bb", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        books.creditUnbacked(issuerA, "note", new BigInteger("20"));
        books.deposit(issuerA, "copper", new BigInteger("2000"));
        BigInteger diamondReserve = books.supply("diamond").reserve();
        BigInteger noteLiability = books.supply("note").liability();
        SecondaryClearing.Quote quote = SecondaryClearing.priceSwap(
                books.personalDiamondPrice(expensive), books.personalDiamondPrice(cheap), BigInteger.valueOf(3),
                books.unitsPer("note", "diamond"), books.copperPerDiamond());
        assertEquals(0, BigDecimal.ONE.compareTo(quote.residualDiamonds()));

        SwapTrade swap = books.swapPersonalNotes(issuerA, "Aa", "Bb", BigInteger.valueOf(3));

        assertTrue(swap.success(), swap.code());
        assertEquals(BigInteger.ONE, swap.paidNotes());
        assertEquals(BigInteger.valueOf(3), swap.receivedNotes());
        assertEquals(quote.central(), swap.central());
        assertEquals(quote.copper(), swap.copper());
        assertTrue(swap.central().signum() > 0 || swap.copper().signum() > 0);
        assertEquals(new BigInteger("9"), books.personalHolding(expensive.id(), issuerA));
        assertEquals(BigInteger.valueOf(3), books.personalHolding(cheap.id(), issuerA));
        assertEquals(new BigInteger("20").subtract(swap.central()), books.balance(issuerA, "note"));
        assertEquals(new BigInteger("2000").subtract(swap.copper()), books.balance(issuerA, "copper"));
        assertEquals(new BigInteger("7"), books.personalHolding(cheap.id(), issuerB));
        assertEquals(BigInteger.ONE, books.personalHolding(expensive.id(), issuerB));
        assertEquals(swap.central(), books.balance(issuerB, "note"));
        assertEquals(swap.copper(), books.balance(issuerB, "copper"));
        assertEquals(new BigInteger("20"), books.personalReserve(issuerA, "diamond"));
        assertEquals(BigInteger.TEN, books.personalReserve(issuerB, "diamond"));
        assertEquals(new BigInteger("20"), expensive.locked());
        assertEquals(BigInteger.TEN, cheap.locked());
        assertEquals(BigInteger.TEN, expensive.circulation());
        assertEquals(BigInteger.TEN, cheap.circulation());
        assertEquals(diamondReserve, books.supply("diamond").reserve());
        assertEquals(noteLiability, books.supply("note").liability());
        assertEquals("self", books.swapPersonalNotes(issuerB, "Aa", "Bb", BigInteger.ONE).code());
        books.checkInvariants();
    }

    @Test
    void fractionalCrossRatePaysCopperChange() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        PersonalNote pay = issue(books, issuerA, "Aa", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        PersonalNote receive = issue(books, issuerB, "Bb", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        books.creditUnbacked(buyer, "note", new BigInteger("100"));
        assertTrue(books.buyPersonalNote(buyer, "Bb", BigInteger.TEN).success());
        assertEquals(0, new BigDecimal("1.1").compareTo(books.personalDiamondPrice(receive)));
        books.deposit(issuerA, "copper", new BigInteger("2000"));
        books.creditUnbacked(issuerA, "note", new BigInteger("20"));
        BigInteger reserveA = books.personalReserve(issuerA, "diamond");
        BigInteger reserveB = books.personalReserve(issuerB, "diamond");
        BigInteger lockedA = pay.locked();
        BigInteger lockedB = receive.locked();
        BigInteger diamondStock = books.supply("diamond").reserve();
        BigInteger buyerNotes = books.balance(buyer, "note");
        SecondaryClearing.Quote quote = SecondaryClearing.priceSwap(
                books.personalDiamondPrice(pay), books.personalDiamondPrice(receive), BigInteger.ONE,
                books.unitsPer("note", "diamond"), books.copperPerDiamond());
        assertTrue(quote.residualDiamonds().signum() > 0);
        assertTrue(quote.residualDiamonds().compareTo(books.personalDiamondPrice(receive)) < 0);
        assertTrue(quote.copper().signum() > 0);

        SwapTrade swap = books.swapPersonalNotes(issuerA, "Aa", "Bb", BigInteger.ONE);

        assertTrue(swap.success(), swap.code());
        assertEquals(BigInteger.ONE, swap.paidNotes());
        assertEquals(BigInteger.ONE, swap.receivedNotes());
        assertEquals(quote.central(), swap.central());
        assertEquals(quote.copper(), swap.copper());
        assertEquals(new BigInteger("9"), books.personalHolding(pay.id(), issuerA));
        assertEquals(BigInteger.ONE, books.personalHolding(receive.id(), issuerA));
        assertEquals(new BigInteger("2000").subtract(quote.copper()), books.balance(issuerA, "copper"));
        assertEquals(new BigInteger("20").subtract(quote.central()), books.balance(issuerA, "note"));
        assertEquals(new BigInteger("9"), books.personalHolding(receive.id(), buyer));
        assertEquals(BigInteger.ONE, books.personalHolding(pay.id(), buyer));
        assertEquals(quote.copper(), books.balance(buyer, "copper"));
        assertEquals(buyerNotes.add(quote.central()), books.balance(buyer, "note"));
        assertEquals(reserveA, books.personalReserve(issuerA, "diamond"));
        assertEquals(reserveB, books.personalReserve(issuerB, "diamond"));
        assertEquals(lockedA, pay.locked());
        assertEquals(lockedB, receive.locked());
        assertEquals(diamondStock, books.supply("diamond").reserve());
        assertEquals(BigInteger.TEN, pay.circulation());
        assertEquals(BigInteger.TEN, receive.circulation());
        books.checkInvariants();
    }

    @Test
    void redemptionAtMarketPriceUnlocksReserveAndRetiresCirculation() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        PersonalNote note = issue(books, issuerA, "Aa", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        assertEquals(0, BigDecimal.ONE.compareTo(books.personalDiamondPrice(note)));

        Redemption redeemed = books.redeemPersonal(issuerA, "Aa", new BigInteger("4"), "diamond");

        assertTrue(redeemed.success(), redeemed.code());
        assertFalse(redeemed.discounted());
        assertEquals(new BigInteger("4"), redeemed.diamonds());
        assertEquals(BigInteger.ZERO, redeemed.copper());
        assertEquals(new BigInteger("6"), note.circulation());
        assertEquals(new BigInteger("6"), note.locked());
        assertEquals(new BigInteger("6"), books.personalReserve(issuerA, "diamond"));
        assertEquals(new BigInteger("6"), books.personalHolding(note.id(), issuerA));
        assertEquals(new BigInteger("4"), books.balance(issuerA, "diamond"));
        assertEquals(new BigInteger("1000"), books.supply("diamond").reserve());
        assertEquals(new BigInteger("4"), books.supply("diamond").depositVault());
        books.checkInvariants();
    }

    @Test
    void shortReserveRedeemsAtADiscountAndDoesNotPayMoreThanPosted() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        PersonalNote note = issue(books, issuerB, "Bb", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        books.creditUnbacked(buyer, "note", new BigInteger("100"));
        assertTrue(books.buyPersonalNote(buyer, "Bb", BigInteger.TEN).success());
        assertEquals(0, new BigDecimal("1.1").compareTo(books.personalDiamondPrice(note)));
        BigDecimal market = books.personalDiamondPrice(note).multiply(BigDecimal.TEN);

        Redemption redeemed = books.redeemPersonal(buyer, "Bb", BigInteger.TEN, "diamond");

        assertTrue(redeemed.success(), redeemed.code());
        assertTrue(redeemed.discounted());
        assertEquals(BigInteger.TEN, redeemed.diamonds());
        assertTrue(new BigDecimal(redeemed.diamonds()).compareTo(market) < 0);
        assertEquals(BigInteger.ZERO, note.circulation());
        assertEquals(BigInteger.ZERO, note.locked());
        assertEquals(BigInteger.ZERO, books.personalReserve(issuerB, "diamond"));
        assertEquals(BigInteger.ZERO, books.personalHolding(note.id(), buyer));
        assertEquals(BigInteger.TEN, books.balance(buyer, "diamond"));
        books.checkInvariants();
    }

    @Test
    void payingWithAtoBuyBLiftsBAndLeansOnAThenASaleUsesTheNewQuote() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        PersonalNote noteA = issue(books, issuerA, "Aa", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        PersonalNote noteB = issue(books, issuerB, "Bb", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        BigDecimal purchaseA = books.personalDiamondPrice(noteA);
        BigDecimal purchaseB = books.personalDiamondPrice(noteB);
        assertEquals(0, BigDecimal.ONE.compareTo(purchaseA));
        assertEquals(0, BigDecimal.ONE.compareTo(purchaseB));

        SwapTrade bought = books.swapPersonalNotes(issuerA, "Aa", "Bb", BigInteger.TEN);

        assertTrue(bought.success(), bought.code());
        assertEquals(BigInteger.TEN, bought.paidNotes());
        assertEquals(BigInteger.TEN, bought.receivedNotes());
        BigDecimal afterA = books.personalDiamondPrice(noteA);
        BigDecimal afterB = books.personalDiamondPrice(noteB);
        assertTrue(afterB.compareTo(purchaseB) > 0, afterB.toPlainString());
        assertTrue(afterA.compareTo(purchaseA) < 0, afterA.toPlainString());
        assertEquals(0, new BigDecimal("1.1").compareTo(afterB));
        assertEquals(0, new BigDecimal("0.9").compareTo(afterA));
        assertEquals(BigInteger.TEN, noteA.circulation());
        assertEquals(BigInteger.TEN, noteB.circulation());
        assertEquals(BigInteger.TEN, books.personalReserve(issuerA, "diamond"));
        assertEquals(BigInteger.TEN, books.personalReserve(issuerB, "diamond"));

        books.creditUnbacked(issuerB, "note", new BigInteger("50"));
        books.deposit(issuerB, "copper", new BigInteger("5000"));
        BigDecimal live = books.personalDiamondPrice(noteB);
        assertEquals(0, afterB.compareTo(live));
        BigInteger[] atLive = cash(books, live, BigInteger.TEN);
        BigInteger[] atPurchase = cash(books, purchaseB, BigInteger.TEN);
        assertTrue(atLive[0].compareTo(atPurchase[0]) != 0 || atLive[1].compareTo(atPurchase[1]) != 0);

        NoteTrade sold = books.sellPersonalNote(issuerA, "Bb", BigInteger.TEN);

        assertTrue(sold.success(), sold.code());
        assertEquals(atLive[0], sold.centralPaid());
        assertEquals(atLive[1], sold.copperPaid());
        assertEquals(BigInteger.ZERO, books.personalHolding(noteB.id(), issuerA));
        assertEquals(BigInteger.TEN, books.personalHolding(noteB.id(), issuerB));
        assertTrue(books.personalDiamondPrice(noteB).compareTo(live) < 0);
        books.checkInvariants();
    }

    /** Central notes and copper for a diamond quote, using the same rounding as a live sale. */
    private static BigInteger[] cash(BankBooks books, BigDecimal diamondEach, BigInteger amount) {
        BigDecimal exact = diamondEach.multiply(new BigDecimal(amount), FxMath.MC)
                .multiply(books.unitsPer("note", "diamond"), FxMath.MC);
        BigInteger central = exact.setScale(0, RoundingMode.FLOOR).toBigInteger();
        if (central.signum() < 0) {
            central = BigInteger.ZERO;
        }
        BigInteger copper = exact.subtract(new BigDecimal(central))
                .multiply(new BigDecimal(books.copperPerDiamond()), FxMath.MC)
                .setScale(0, RoundingMode.HALF_UP).toBigInteger();
        if (copper.signum() < 0) {
            copper = BigInteger.ZERO;
        }
        return new BigInteger[] {central, copper};
    }

    @Test
    void paperStillStacksAndACopyIsRejected() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        books.creditUnbacked(issuerA, "note", BigInteger.valueOf(64));
        var issued = books.issueCentralPaper(issuerA, BigInteger.valueOf(64));
        PresentedNote paper = new PresentedNote("note", null, BigInteger.ONE);
        assertEquals(NotePaper.signature(paper, "银行券"), NotePaper.signature(paper, "银行券"));
        assertEquals(NoteSeries.key(paper), NoteSeries.key(new PresentedNote("note", null, issued.face())));
        assertNotEquals(NoteSeries.key(paper), NoteSeries.key(new PresentedNote(null, UUID.randomUUID(), BigInteger.ONE)));

        TxResult deposited = books.redeemPaper(buyer, paper, BigInteger.valueOf(64));
        TxResult copy = books.redeemPaper(buyer, paper, BigInteger.valueOf(64));

        assertTrue(deposited.success());
        assertEquals(BigInteger.valueOf(64), books.balance(buyer, "note"));
        assertEquals("note_spent", copy.code());
        assertEquals(BigInteger.valueOf(64), books.balance(buyer, "note"));
        books.checkInvariants();
    }

    private static PersonalNote issue(BankBooks books, UUID issuer, String name, BigInteger par,
                                      BigInteger reserve, BigInteger amount) {
        books.setPersonalReserve(issuer, "diamond", reserve);
        assertTrue(books.createPersonalNote(issuer, name, false, par).success());
        assertTrue(books.issuePersonalNote(issuer, amount).success());
        return books.personalNote(issuer);
    }
}
