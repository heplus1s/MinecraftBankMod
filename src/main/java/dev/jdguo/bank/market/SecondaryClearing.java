package dev.jdguo.bank.market;

import dev.jdguo.bank.fx.FxMath;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Secondary-market clearing for personal notes. A swap moves book-entry claims and settles
 * the indivisible remainder in central notes and copper. It does not touch issuer reserves.
 * Issuance and {@code 通兑} are the only paths that post or unlock reserves.
 * Later stock and futures books can reuse this shape: the secondary market transfers claims,
 * and only primary issue or redemption moves the posted reserve.
 */
public final class SecondaryClearing {
    private SecondaryClearing() {}

    /**
     * Price of {@code receiveAmount} notes at {@code receivePrice}, paid with as many whole
     * notes at {@code payPrice} as fit, plus central notes and copper for the remainder.
     * Returns null when a whole pay-note does not fit or a price is missing.
     */
    public static Quote priceSwap(BigDecimal payPrice, BigDecimal receivePrice, BigInteger receiveAmount,
                                  BigDecimal notesPerDiamond, BigInteger copperPerDiamond) {
        if (payPrice == null || receivePrice == null || receiveAmount == null
                || payPrice.signum() <= 0 || receivePrice.signum() <= 0 || receiveAmount.signum() <= 0
                || notesPerDiamond == null || notesPerDiamond.signum() <= 0
                || copperPerDiamond == null || copperPerDiamond.signum() <= 0) {
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
        BigDecimal exactCentral = residual.multiply(notesPerDiamond, FxMath.MC);
        BigInteger central = exactCentral.setScale(0, RoundingMode.FLOOR).toBigInteger();
        if (central.signum() < 0) {
            central = BigInteger.ZERO;
        }
        BigDecimal fraction = exactCentral.subtract(new BigDecimal(central));
        BigInteger copper = fraction.multiply(new BigDecimal(copperPerDiamond), FxMath.MC)
                .setScale(0, RoundingMode.HALF_UP).toBigInteger();
        if (copper.signum() < 0) {
            copper = BigInteger.ZERO;
        }
        return new Quote(payNotes, central, copper, residual);
    }

    public record Quote(BigInteger payNotes, BigInteger central, BigInteger copper, BigDecimal residualDiamonds) {
    }
}
