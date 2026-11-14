package dev.jdguo.bank.ledger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Settlement scale. Every booked amount is an exact multiple of 0.0001.
 * Integer zero is stored and shown as {@code 0}, never as a run of decimal zeros.
 */
public final class Money {
    public static final int SCALE = 4;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);
    public static final BigDecimal UNIT = new BigDecimal("0.0001");

    private Money() {}

    public static BigDecimal of(BigInteger units) {
        if (units == null) {
            return ZERO;
        }
        return new BigDecimal(units).setScale(SCALE);
    }

    public static BigDecimal of(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Accepts a whole number or a decimal. More than four fractional digits are rounded to scale. */
    public static BigDecimal parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty amount");
        }
        return of(new BigDecimal(raw.trim()));
    }

    /**
     * Whole units toward zero. An amount that is already an integer returns that integer,
     * so a balance of {@code 64.0000} is still {@code 64}.
     */
    public static BigInteger units(BigDecimal value) {
        return of(value).setScale(0, RoundingMode.DOWN).toBigInteger();
    }

    /** Ledger text. {@code 0}, {@code 1000}, or {@code 0.009}. */
    public static String stored(BigDecimal value) {
        BigDecimal scaled = of(value).stripTrailingZeros();
        if (scaled.scale() < 0 || scaled.signum() == 0) {
            scaled = scaled.setScale(0);
        }
        return scaled.toPlainString();
    }

    /**
     * Player-facing amount. Integral values use the existing compact form.
     * A fraction keeps at most four decimals, with trailing zeros removed.
     */
    public static String display(BigDecimal value) {
        BigDecimal scaled = of(value);
        if (scaled.signum() == 0) {
            return "0";
        }
        BigDecimal stripped = scaled.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            return CompactFormat.format(stripped.setScale(0).toBigInteger());
        }
        boolean negative = stripped.signum() < 0;
        if (negative) {
            stripped = stripped.negate();
        }
        String plain = stripped.toPlainString();
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        String fraction = dot < 0 ? "" : plain.substring(dot);
        return (negative ? "-" : "") + grouped(whole) + fraction;
    }

    private static String grouped(String digits) {
        StringBuilder sb = new StringBuilder();
        int lead = digits.length() % 3;
        if (lead == 0) {
            lead = 3;
        }
        sb.append(digits, 0, Math.min(lead, digits.length()));
        for (int i = lead; i < digits.length(); i += 3) {
            sb.append(',').append(digits, i, i + 3);
        }
        return sb.toString();
    }
}
