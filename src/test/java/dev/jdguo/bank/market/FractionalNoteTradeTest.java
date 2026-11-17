package dev.jdguo.bank.market;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FractionalNoteTradeTest {
    @Test void residualUsesCentralQuoteNotCopperRatio() {
        var q = SecondaryClearing.priceSwap(BigDecimal.ONE, new BigDecimal("1.1"), BigInteger.ONE,
                new BigDecimal("2.5"), BigInteger.valueOf(100));
        assertEquals(0, new BigDecimal("0.25").compareTo(q.centralExact()));
        assertEquals(BigInteger.ZERO, q.copper());
    }
    @Test void missingOrInvalidCopperQuoteDoesNotBlockCentralNoteChange() {
        for (BigInteger copper : new BigInteger[] { null, BigInteger.ZERO, BigInteger.valueOf(-1) }) {
            var quote = SecondaryClearing.priceSwap(BigDecimal.ONE, new BigDecimal("1.1"), BigInteger.ONE,
                    new BigDecimal("2.5"), copper);
            assertNotNull(quote);
            assertEquals(0, new BigDecimal("0.25").compareTo(quote.centralExact()));
            assertEquals(BigInteger.ZERO, quote.copper());
        }
        assertNull(SecondaryClearing.priceSwap(BigDecimal.ONE, new BigDecimal("1.1"), BigInteger.ONE, null, null));
    }
    @Test void subQuantumResidualIsNotFree() {
        var q = SecondaryClearing.priceSwap(BigDecimal.ONE, new BigDecimal("1.00000001"), BigInteger.ONE,
                new BigDecimal("0.01"), BigInteger.valueOf(100));
        assertEquals(0, Money.UNIT.compareTo(q.centralExact()));
        assertEquals(BigInteger.ZERO, q.copper());
    }
    @Test void tradeMovesExistingFractionalNotesAndNeverCopperOrCollateral() {
        BankBooks b = BankBooks.create(BankConfig.defaults());
        UUID issuer = UUID.randomUUID(), buyer = UUID.randomUUID();
        assertTrue(b.createPersonalNote(issuer, "测试", false, BigInteger.ONE).success());
        assertTrue(b.deposit(issuer, "diamond", BigInteger.TEN).success());
        assertTrue(b.depositPersonalReserveExact(issuer, BigDecimal.TEN).success());
        b.creditHeld(issuer,"note",BigInteger.TEN);
        assertTrue(b.depositCentralReserve(issuer,BigInteger.TEN).success());
        assertTrue(b.issuePersonalNote(issuer,BigInteger.TEN).success());
        b.creditHeld(buyer,"note",BigInteger.TEN);
        BigDecimal quote=b.personalDiamondPrice(b.personalNote(issuer)).multiply(b.unitsPer("note","diamond")).setScale(Money.SCALE,RoundingMode.CEILING);
        BigDecimal liability=b.supply("note").liabilityExact(), custody=b.supply("diamond").depositVaultExact();
        var result=b.buyPersonalNote(buyer,"测试",BigInteger.ONE);
        assertTrue(result.success(),result.code());
        assertEquals(0,quote.compareTo(result.centralPaidExact()));
        assertEquals(0,BigDecimal.TEN.subtract(quote).compareTo(b.balanceExact(buyer,"note")));
        assertEquals(0,quote.compareTo(b.balanceExact(issuer,"note")));
        assertEquals(BigInteger.ZERO,result.copperPaid());
        assertEquals(BigInteger.ZERO,b.balance(buyer,"copper"));
        assertEquals(0,liability.compareTo(b.supply("note").liabilityExact()));
        assertEquals(0,custody.compareTo(b.supply("diamond").depositVaultExact()));
        b.checkInvariants();
    }
    @Test void copperCannotSubstituteForMissingCentralNotes() {
        BankBooks b=BankBooks.create(BankConfig.defaults()); UUID issuer=UUID.randomUUID(), buyer=UUID.randomUUID();
        b.createPersonalNote(issuer,"待售",false,BigInteger.ONE);b.deposit(issuer,"diamond",BigInteger.TEN);
        b.depositPersonalReserveExact(issuer,BigDecimal.TEN);b.creditHeld(issuer,"note",BigInteger.TEN);
        b.depositCentralReserve(issuer,BigInteger.TEN);b.issuePersonalNote(issuer,BigInteger.TEN);
        b.deposit(buyer,"copper",BigInteger.valueOf(100000));
        String before=b.toJson().toString();
        assertFalse(b.buyPersonalNote(buyer,"待售",BigInteger.ONE).success());
        assertEquals(before,b.toJson().toString());
    }
}
