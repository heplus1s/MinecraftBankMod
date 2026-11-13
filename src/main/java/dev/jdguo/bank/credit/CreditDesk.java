package dev.jdguo.bank.credit;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** How many virtual units a commodity vault can still underwrite. */
public final class CreditDesk {
    private CreditDesk() {}

    public static BigInteger backingUnitsNeeded(BigInteger notes, BigDecimal par, BigDecimal haircut, BigDecimal minRatio) {
        if (notes.signum() <= 0 || par.signum() <= 0 || haircut.signum() <= 0) {
            return BigInteger.ZERO;
        }
        BigDecimal units = new BigDecimal(notes).multiply(minRatio)
                .divide(haircut.multiply(par), 0, RoundingMode.CEILING);
        BigInteger needed = units.toBigInteger();
        return needed.signum() < 1 ? BigInteger.ONE : needed;
    }

    public static BigInteger maxLiabilities(BigInteger backingReserve, BigDecimal par, BigDecimal haircut, BigDecimal minRatio) {
        if (backingReserve.signum() <= 0 || par.signum() <= 0 || haircut.signum() <= 0 || minRatio.signum() <= 0) {
            return BigInteger.ZERO;
        }
        return new BigDecimal(backingReserve)
                .multiply(haircut)
                .multiply(par)
                .divide(minRatio, 0, RoundingMode.FLOOR)
                .toBigInteger();
    }

    public static BigInteger room(BigInteger maxLiabilities, BigInteger currentLiabilities) {
        BigInteger room = maxLiabilities.subtract(currentLiabilities);
        return room.signum() < 0 ? BigInteger.ZERO : room;
    }
}
