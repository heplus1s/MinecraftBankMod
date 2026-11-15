package dev.jdguo.bank.fx;

import dev.jdguo.bank.ledger.Money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Difference-driven exchange rates, lifted off the scoreboard.
 *
 * <p>The datapack computed an effective supply
 * {@code EC = R + floor((R - L) * alpha / 1000)} and quoted copper per diamond as
 * {@code EC_copper / EC_diamond}. With a zero opening liability that shrinks EC when
 * the bank prints, so unbacked notes would look stronger, not weaker.
 *
 * <p>This mod uses the inflationary twin, still linear in the same reserve gap:
 * {@code V = R + (L - R) * alpha / 1000}. Opening books are seeded with {@code L = R},
 * so at the default alpha of 1000‰ commodity quotes still open at {@code R_i / R_numeraire}
 * (copper/diamond = 1000), the same number the datapack shows. Printing liabilities
 * without reserves raises V and weakens that currency. Alpha 0 ignores liabilities and
 * pegs V to reserves. There is no 32-bit floor; the division keeps 50 decimal digits
 * and settlement rounds the payer's cost to four decimal places.
 *
 * <p>A virtual currency is quoted off its backing commodity:
 * {@code scale = (1 - alpha/1000) + (alpha/1000) * (L / R)}, then multiplied by par.
 * The worse of that scale and {@code L / vaultCover} is used, so an empty diamond vault
 * (a bank run) also blows the peg out. Clamps are optional and default to off.
 */
public final class FxMath {
    public static final MathContext MC = new MathContext(50, RoundingMode.HALF_UP);
    public static final BigDecimal THOUSAND = new BigDecimal("1000");

    private FxMath() {}

    public static BigDecimal commodityAbundance(BigInteger reserve, BigInteger liability, BigDecimal alphaPermille) {
        BigDecimal r = new BigDecimal(reserve);
        BigDecimal l = new BigDecimal(liability);
        BigDecimal a = alphaPermille.divide(THOUSAND, MC);
        return r.multiply(BigDecimal.ONE.subtract(a), MC).add(l.multiply(a, MC), MC);
    }

    /**
     * @return relative abundance of a virtual unit versus its par peg, or null when the
     *         currency has collapsed (no positive reserve left while notes are outstanding
     *         and alpha passes liabilities through).
     */
    public static BigDecimal virtualScale(BigInteger reserve, BigInteger liability, BigDecimal alphaPermille) {
        BigDecimal a = alphaPermille.divide(THOUSAND, MC);
        if (reserve.signum() <= 0) {
            if (liability.signum() > 0 && a.signum() > 0) {
                return null;
            }
            return BigDecimal.ONE;
        }
        BigDecimal ratio = new BigDecimal(liability).divide(new BigDecimal(reserve), MC);
        BigDecimal scale = BigDecimal.ONE.subtract(a, MC).add(a.multiply(ratio, MC), MC);
        if (scale.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return scale;
    }

    public static BigDecimal quote(BigDecimal abundancePay, BigDecimal abundanceReceive,
                                   BigDecimal clampMin, BigDecimal clampMax) {
        if (abundancePay == null || abundanceReceive == null) {
            return null;
        }
        if (abundancePay.compareTo(BigDecimal.ZERO) <= 0 || abundanceReceive.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal q = abundancePay.divide(abundanceReceive, MC);
        if (clampMin != null && q.compareTo(clampMin) < 0) {
            q = clampMin;
        }
        if (clampMax != null && q.compareTo(clampMax) > 0) {
            q = clampMax;
        }
        return q;
    }

    /**
     * Units of {@code pay} required to receive {@code quantity} of the other currency, fee included,
     * settled at four decimal places. Null when the market has collapsed or the settled cost is not positive.
     */
    public static BigDecimal settle(BigInteger quantity, BigDecimal abundancePay, BigDecimal abundanceReceive,
                                    BigDecimal feeFraction, BigDecimal clampMin, BigDecimal clampMax) {
        if (quantity == null || quantity.signum() <= 0) {
            return null;
        }
        BigDecimal q = quote(abundancePay, abundanceReceive, clampMin, clampMax);
        if (q == null) {
            return null;
        }
        BigDecimal raw = new BigDecimal(quantity).multiply(q, MC).multiply(BigDecimal.ONE.add(feeFraction), MC);
        BigDecimal settled = Money.of(raw);
        return settled.signum() > 0 ? settled : null;
    }

    /** Whole units of {@link #settle}, toward the nearest unit. A sub-unit price can be zero here. */
    public static BigInteger cost(BigInteger quantity, BigDecimal abundancePay, BigDecimal abundanceReceive,
                                  BigDecimal feeFraction, BigDecimal clampMin, BigDecimal clampMax) {
        BigDecimal settled = settle(quantity, abundancePay, abundanceReceive, feeFraction, clampMin, clampMax);
        if (settled == null) {
            return null;
        }
        return settled.setScale(0, RoundingMode.HALF_UP).toBigInteger();
    }
}
