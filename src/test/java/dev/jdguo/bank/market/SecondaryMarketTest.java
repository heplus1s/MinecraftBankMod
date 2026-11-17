package dev.jdguo.bank.market;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.instruments.NotePaper;
import dev.jdguo.bank.instruments.NoteSeries;
import dev.jdguo.bank.instruments.PresentedNote;
import dev.jdguo.bank.fx.FxMath;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.Money;
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
        books.creditHeld(issuerA, "note", new BigInteger("20"));
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
        assertTrue(swap.centralExact().signum() > 0);
        assertEquals(new BigInteger("9"), books.personalHolding(expensive.id(), issuerA));
        assertEquals(BigInteger.valueOf(3), books.personalHolding(cheap.id(), issuerA));
        assertEquals(0, new BigDecimal("20").subtract(swap.centralExact()).compareTo(books.balanceExact(issuerA, "note")));
        assertEquals(new BigInteger("2000").subtract(swap.copper()), books.balance(issuerA, "copper"));
        assertEquals(new BigInteger("7"), books.personalHolding(cheap.id(), issuerB));
        assertEquals(BigInteger.ONE, books.personalHolding(expensive.id(), issuerB));
        assertEquals(0, swap.centralExact().compareTo(books.balanceExact(issuerB, "note")));
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
    void fractionalCrossRatePaysCentralNoteChange() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        PersonalNote pay = issue(books, issuerA, "Aa", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        PersonalNote receive = issue(books, issuerB, "Bb", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        books.deposit(buyer, "copper", new BigInteger("20000"));
        books.creditHeld(buyer, "note", new BigInteger("100"));
        assertTrue(books.buyPersonalNote(buyer, "Bb", BigInteger.TEN).success());
        assertEquals(0, BigDecimal.ONE.compareTo(books.personalDiamondPrice(receive)));
        // A real collateral deposit, not trading-flow inflation, creates the fractional cross rate.
        assertTrue(books.deposit(issuerB, "diamond", BigInteger.ONE).success());
        assertTrue(books.depositPersonalReserveExact(issuerB, BigDecimal.ONE).success());
        assertEquals(0, new BigDecimal("1.1").compareTo(books.personalDiamondPrice(receive)));
        books.deposit(issuerA, "copper", new BigInteger("2000"));
        books.creditHeld(issuerA, "note", new BigInteger("20"));
        BigInteger reserveA = books.personalReserve(issuerA, "diamond");
        BigInteger reserveB = books.personalReserve(issuerB, "diamond");
        BigInteger lockedA = pay.locked();
        BigInteger lockedB = receive.locked();
        BigInteger diamondStock = books.supply("diamond").reserve();
        BigDecimal buyerNotes = books.balanceExact(buyer, "note");
        BigInteger buyerCopper = books.balance(buyer, "copper");
        SecondaryClearing.Quote quote = SecondaryClearing.priceSwap(
                books.personalDiamondPrice(pay), books.personalDiamondPrice(receive), BigInteger.ONE,
                books.unitsPer("note", "diamond"), books.copperPerDiamond());
        assertTrue(quote.residualDiamonds().signum() > 0);
        assertTrue(quote.residualDiamonds().compareTo(books.personalDiamondPrice(receive)) < 0);
        assertEquals(BigInteger.ZERO, quote.copper());
        assertTrue(quote.centralExact().signum() > 0);

        // A secondary holder must explicitly offer inventory before another player may swap it.
        assertTrue(books.listPersonal(buyer, "Bb", BigInteger.TEN).success());
        SwapTrade swap = books.swapPersonalNotes(issuerA, "Aa", "Bb", BigInteger.ONE);

        assertTrue(swap.success(), swap.code());
        assertEquals(BigInteger.ONE, swap.paidNotes());
        assertEquals(BigInteger.ONE, swap.receivedNotes());
        assertEquals(quote.central(), swap.central());
        assertEquals(quote.copper(), swap.copper());
        assertEquals(new BigInteger("9"), books.personalHolding(pay.id(), issuerA));
        assertEquals(BigInteger.ONE, books.personalHolding(receive.id(), issuerA));
        assertEquals(new BigInteger("2000").subtract(quote.copper()), books.balance(issuerA, "copper"));
        assertEquals(0, new BigDecimal("20").subtract(quote.centralExact()).compareTo(books.balanceExact(issuerA, "note")));
        assertEquals(new BigInteger("9"), books.personalHolding(receive.id(), buyer));
        assertEquals(BigInteger.ONE, books.personalHolding(pay.id(), buyer));
        assertEquals(buyerCopper.add(quote.copper()), books.balance(buyer, "copper"));
        assertEquals(0, buyerNotes.add(quote.centralExact()).compareTo(books.balanceExact(buyer, "note")));
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
    void redemptionAtBackingPriceRecoversNotesAndCreditsSavingsWithoutDuplicatingCustody() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        PersonalNote note = issue(books, issuerA, "Aa", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        assertEquals(0, BigDecimal.ONE.compareTo(books.personalDiamondPrice(note)));
        BigDecimal vault = books.supply("diamond").depositVaultExact();
        assertEquals("route_to_savings", books.redeemPersonal(issuerA, "Aa", new BigInteger("4"), "diamond").code());

        TxResult redeemed = books.redeemPersonalToSavings(issuerA, note.id(), new BigDecimal("4"), "secondary-redeem");

        assertTrue(redeemed.success(), redeemed.code());
        assertEquals(BigInteger.TEN, note.circulation()); // Issued total is auditable; recovered currency is not active.
        assertEquals(0, new BigDecimal("6").compareTo(books.personalActiveExact(note)));
        assertEquals(0, new BigDecimal("4").compareTo(books.personalRecovered(note.id())));
        assertEquals(new BigInteger("6"), books.personalReserve(issuerA, "diamond"));
        assertEquals(new BigInteger("6"), books.personalHolding(note.id(), issuerA));
        assertEquals(new BigInteger("4"), books.balance(issuerA, "diamond"));
        assertEquals(new BigInteger("1000"), books.supply("diamond").reserve());
        assertEquals(0, vault.compareTo(books.supply("diamond").depositVaultExact()));
        assertEquals("settled", books.personalSettlementReceipt("secondary-redeem").get("status").getAsString());
        assertFalse(books.noteHasPending(note.id()));
        books.checkInvariants();
    }

    @Test
    void dilutionRedemptionPaysOnlyRealBackingAndNeverTheTargetValue() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        PersonalNote note = issue(books, issuerB, "Bb", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        // Explicit unbacked issuance lowers actual support from 1 to 0.5 without making assets.
        assertTrue(books.issuePersonalNoteUnbacked(issuerB, BigInteger.TEN).success());
        books.deposit(buyer, "copper", new BigInteger("20000"));
        books.creditHeld(buyer, "note", new BigInteger("100"));
        assertTrue(books.buyPersonalNote(buyer, "Bb", BigInteger.TEN).success());
        assertEquals(0, new BigDecimal("0.5").compareTo(books.personalDiamondPrice(note)));
        BigDecimal reserveBefore = books.effectiveBackingExact(note);
        BigDecimal vaultBefore = books.supply("diamond").depositVaultExact();

        TxResult redeemed = books.redeemPersonalToSavings(buyer, note.id(), BigDecimal.TEN, "diluted-redeem");

        assertTrue(redeemed.success(), redeemed.code());
        assertEquals(0, new BigDecimal("5").compareTo(books.balanceExact(buyer, "diamond")));
        assertTrue(books.balanceExact(buyer, "diamond").compareTo(reserveBefore) <= 0);
        assertEquals(0, BigDecimal.TEN.compareTo(books.personalActiveExact(note)));
        assertEquals(0, BigDecimal.TEN.compareTo(books.personalRecovered(note.id())));
        assertEquals(new BigInteger("5"), books.personalReserve(issuerB, "diamond"));
        assertEquals(BigInteger.ZERO, books.personalHolding(note.id(), buyer));
        assertEquals(0, vaultBefore.compareTo(books.supply("diamond").depositVaultExact()));
        assertEquals(0, new BigDecimal("0.5").compareTo(books.personalDiamondPrice(note)));
        books.checkInvariants();
    }

    @Test
    void ownershipSwapDoesNotInventValueAndASaleUsesNewRealBackingQuote() {
        BankBooks books = BankBooks.create(BankConfig.defaults());
        PersonalNote noteA = issue(books, issuerA, "Aa", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        PersonalNote noteB = issue(books, issuerB, "Bb", BigInteger.ONE, BigInteger.TEN, BigInteger.TEN);
        BigDecimal purchaseA = books.personalDiamondPrice(noteA);
        BigDecimal purchaseB = books.personalDiamondPrice(noteB);
        assertEquals(0, BigDecimal.ONE.compareTo(purchaseA));
        assertEquals(0, BigDecimal.ONE.compareTo(purchaseB));
        books.creditHeld(buyer, "note", new BigInteger("2000"));

        SwapTrade bought = books.swapPersonalNotes(issuerA, "Aa", "Bb", BigInteger.TEN);

        assertTrue(bought.success(), bought.code());
        assertEquals(BigInteger.TEN, bought.paidNotes());
        assertEquals(BigInteger.TEN, bought.receivedNotes());
        BigDecimal afterA = books.personalDiamondPrice(noteA);
        BigDecimal afterB = books.personalDiamondPrice(noteB);
        assertEquals(0, afterB.compareTo(purchaseB));
        assertEquals(0, afterA.compareTo(purchaseA));
        assertEquals(BigInteger.TEN, noteA.circulation());
        assertEquals(BigInteger.TEN, noteB.circulation());
        assertEquals(BigInteger.TEN, books.personalReserve(issuerA, "diamond"));
        assertEquals(BigInteger.TEN, books.personalReserve(issuerB, "diamond"));

        books.creditHeld(issuerB, "note", new BigInteger("50"));
        books.deposit(issuerB, "copper", new BigInteger("5000"));
        assertTrue(books.deposit(issuerB, "diamond", BigInteger.ONE).success());
        assertTrue(books.depositPersonalReserveExact(issuerB, BigDecimal.ONE).success());
        BigDecimal live = books.personalDiamondPrice(noteB);
        assertTrue(live.compareTo(afterB) > 0);
        BigInteger[] atLive = cash(books, live, BigInteger.TEN);
        BigInteger[] atPurchase = cash(books, purchaseB, BigInteger.TEN);
        assertTrue(atLive[0].compareTo(atPurchase[0]) != 0 || atLive[1].compareTo(atPurchase[1]) != 0);

        NoteTrade sold = books.sellPersonalNote(issuerA, "Bb", BigInteger.TEN);

        assertTrue(sold.success(), sold.code());
        assertEquals(atLive[0], sold.centralPaid());
        assertEquals(BigInteger.ZERO, sold.copperPaid());
        assertEquals(0, live.multiply(BigDecimal.TEN).multiply(books.unitsPer("note", "diamond")).setScale(Money.SCALE, RoundingMode.CEILING).compareTo(sold.centralPaidExact()));
        assertEquals(BigInteger.ZERO, books.personalHolding(noteB.id(), issuerA));
        assertEquals(BigInteger.TEN, books.personalHolding(noteB.id(), issuerB));
        assertEquals(0, books.personalDiamondPrice(noteB).compareTo(live));
        books.checkInvariants();
    }

    /** Central notes and copper for a diamond quote, using the same rounding as a live sale. */
    private static BigInteger[] cash(BankBooks books, BigDecimal diamondEach, BigInteger amount) {
        BigDecimal exact = Money.of(diamondEach.multiply(new BigDecimal(amount), FxMath.MC)
                .multiply(books.unitsPer("note", "diamond"), FxMath.MC));
        BigInteger central = exact.setScale(0, RoundingMode.DOWN).toBigInteger();
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
        books.creditHeld(issuerA, "note", BigInteger.valueOf(64));
        var issued = books.issueCentralPaper(issuerA, BigInteger.valueOf(64));
        PresentedNote paper = new PresentedNote("note", null, BigInteger.ONE);
        assertEquals(NotePaper.signature(paper, "央行券"), NotePaper.signature(paper, "央行券"));
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
        assertTrue(books.createPersonalNote(issuer, name, false, par).success());
        assertTrue(books.deposit(issuer, "diamond", reserve).success());
        assertTrue(books.depositPersonalReserveExact(issuer, new BigDecimal(reserve)).success());
        BigInteger cover = amount.multiply(par.max(BigInteger.ONE));
        books.creditHeld(issuer, "note", cover);
        assertTrue(books.depositCentralReserve(issuer, cover).success());
        assertTrue(books.issuePersonalNote(issuer, amount).success());
        return books.personalNote(issuer);
    }
}
