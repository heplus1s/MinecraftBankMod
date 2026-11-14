package dev.jdguo.bank.ledger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Display helper. Scoreboards stay 32-bit; this does not. */
public final class CompactFormat {
    private static final String[] SUFFIX = {"", "k", "M", "B", "T", "Qa", "Qi"};

    private CompactFormat() {}

    public static String format(BigInteger value) {
        if (value.signum() < 0) {
            return "-" + format(value.negate());
        }
        if (value.signum() == 0) {
            return "0";
        }
        String digits = value.toString();
        if (digits.length() <= 5) {
            return withCommas(value);
        }
        if (digits.length() > 18) {
            int exp = digits.length() - 1;
            BigDecimal sci = new BigDecimal(value).movePointLeft(exp).setScale(2, RoundingMode.HALF_UP);
            return plain(sci) + "e" + exp;
        }
        int group = (digits.length() - 1) / 3;
        if (group >= SUFFIX.length) {
            int exp = digits.length() - 1;
            BigDecimal sci = new BigDecimal(value).movePointLeft(exp).setScale(2, RoundingMode.HALF_UP);
            return plain(sci) + "e" + exp;
        }
        BigDecimal scaled = new BigDecimal(value).movePointLeft(group * 3).setScale(2, RoundingMode.HALF_UP);
        return plain(scaled) + SUFFIX[group];
    }

    /** Shown M0, M1, M2, and the same aggregates for a personal note. The ledger stays exact. */
    public static String formatAggregate(BigInteger value) {
        return formatAggregate(value == null ? BigDecimal.ZERO : new BigDecimal(value));
    }

    public static String formatAggregate(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        BigDecimal scaled = value.setScale(Money.SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
        if (scaled.scale() < 0) {
            scaled = scaled.setScale(0);
        }
        boolean negative = scaled.signum() < 0;
        if (negative) {
            scaled = scaled.negate();
        }
        String text = scaled.toPlainString();
        int dot = text.indexOf('.');
        String whole = dot < 0 ? text : text.substring(0, dot);
        String fraction = dot < 0 ? "" : text.substring(dot);
        return (negative ? "-" : "") + withCommas(new BigInteger(whole)) + fraction;
    }

    public static String formatRate(BigDecimal value) {
        if (value == null) {
            return "无法报价";
        }
        int sign = value.signum();
        if (sign < 0) {
            return "-" + formatRate(value.negate());
        }
        if (sign == 0) {
            return "0";
        }
        BigDecimal abs = value.abs();
        if (abs.compareTo(new BigDecimal("100000")) >= 0 || abs.compareTo(new BigDecimal("0.01")) < 0) {
            return format(abs.setScale(0, RoundingMode.HALF_UP).toBigInteger().max(BigInteger.ONE));
        }
        BigDecimal scaled = abs.setScale(2, RoundingMode.HALF_UP);
        return plain(scaled);
    }

    public static String formatRatio(BigDecimal ratio) {
        if (ratio == null) {
            return "∞";
        }
        if (ratio.compareTo(new BigDecimal("100")) > 0) {
            return formatRate(ratio.multiply(new BigDecimal("100"))) + "%";
        }
        BigDecimal pct = ratio.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
        return plain(pct) + "%";
    }

    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0);
        }
        return stripped.toPlainString();
    }

    private static String withCommas(BigInteger value) {
        String digits = value.toString();
        StringBuilder sb = new StringBuilder();
        int lead = digits.length() % 3;
        if (lead == 0) {
            lead = 3;
        }
        sb.append(digits, 0, lead);
        for (int i = lead; i < digits.length(); i += 3) {
            sb.append(',').append(digits, i, i + 3);
        }
        return sb.toString();
    }
}
