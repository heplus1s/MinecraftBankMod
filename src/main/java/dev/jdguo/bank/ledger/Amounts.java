package dev.jdguo.bank.ledger;

import java.math.BigDecimal;
import java.math.BigInteger;

public final class Amounts {
    private Amounts() {}

    public static BigInteger parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("空金额");
        }
        String s = raw.trim().replace("_", "").replace(",", "");
        if (s.isEmpty() || s.charAt(0) == '-') {
            throw new IllegalArgumentException("金额必须为正数");
        }
        if (s.charAt(0) == '+') {
            s = s.substring(1);
        }
        BigInteger mul = BigInteger.ONE;
        char last = s.charAt(s.length() - 1);
        if (Character.isLetter(last)) {
            mul = switch (Character.toLowerCase(last)) {
                case 'k' -> BigInteger.valueOf(1_000L);
                case 'm' -> BigInteger.valueOf(1_000_000L);
                case 'b' -> new BigInteger("1000000000");
                case 't' -> new BigInteger("1000000000000");
                default -> throw new IllegalArgumentException("未知后缀: " + last);
            };
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("金额必须为正数");
        }
        BigDecimal value = new BigDecimal(s).multiply(new BigDecimal(mul));
        BigInteger rounded = value.setScale(0, java.math.RoundingMode.HALF_UP).toBigInteger();
        if (rounded.signum() <= 0) {
            throw new IllegalArgumentException("金额必须为正数");
        }
        return rounded;
    }
}
