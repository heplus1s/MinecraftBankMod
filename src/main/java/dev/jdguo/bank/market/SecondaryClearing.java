package dev.jdguo.bank.market;

import dev.jdguo.bank.fx.FxMath;
import dev.jdguo.bank.ledger.Money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Secondary-market clearing for personal notes. A swap moves book-entry claims and settles
 * the indivisible remainder in fractional central notes. It does not touch issuer reserves.
 * Issuance and {@code 通兑} are the only paths that post or unlock reserves.
 * Later stock and futures books can reuse this shape: the secondary market transfers claims,
 * and only primary issue or redemption moves the posted reserve.
 */
public final class SecondaryClearing {
    private SecondaryClearing() {}

    /**
     * Price of {@code receiveAmount} notes at {@code receivePrice}, paid with as many whole
     * notes at {@code payPrice} as fit, plus fractional central notes for the remainder.
     * Returns null when a whole pay-note does not fit or a required price is missing.
     * The legacy copper-rate argument is retained for source compatibility and is ignored.
     */
    public static Quote priceSwap(BigDecimal payPrice, BigDecimal receivePrice, BigInteger receiveAmount,
                                  BigDecimal notesPerDiamond, BigInteger copperPerDiamond) {
        if (payPrice == null || receivePrice == null || receiveAmount == null
                || payPrice.signum() <= 0 || receivePrice.signum() <= 0 || receiveAmount.signum() <= 0
                || notesPerDiamond == null || notesPerDiamond.signum() <= 0) {
            return null;
        }
        BigDecimal cost = receivePrice.multiply(new BigDecimal(receiveAmount), FxMath.MC);
        BigInteger payNotes = cost.divide(payPrice, FxMath.MC).setScale(0, RoundingMode.FLOOR).toBigInteger();
        if (payNotes.signum() <= 0) {
            return null;
        }
        BigDecimal residual = cost.subtract(payPrice.multiply(new BigDecimal(payNotes), FxMath.MC), FxMath.MC);
        if (residual.signum() < 0) {
            residual = BigDecimal.ZERO;
        }
        // Quote in central notes at ledger precision, never reinterpret a note fraction as copper.
        BigDecimal central = residual.multiply(notesPerDiamond, FxMath.MC).setScale(Money.SCALE, RoundingMode.CEILING);
        return new Quote(payNotes, Money.units(central), BigInteger.ZERO, residual, central);
    }

    public record Quote(BigInteger payNotes, BigInteger central, BigInteger copper, BigDecimal residualDiamonds, BigDecimal centralExact) {
        public Quote(BigInteger payNotes, BigInteger central, BigInteger copper, BigDecimal residualDiamonds) {
            this(payNotes, central, copper, residualDiamonds, Money.of(central));
        }
    }
}
