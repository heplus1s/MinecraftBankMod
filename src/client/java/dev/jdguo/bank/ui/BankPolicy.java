package dev.jdguo.bank.ui;

import java.math.BigDecimal;

/** Client-side syntax validation only; server remains authoritative. */
public final class BankPolicy {
    private BankPolicy() {}
    public static String command(String regime, String target, String lower, String upper) {
        String kind = BankActions.regimeToken(regime);
        BigDecimal rate = positive(target);
        BigDecimal lo = "peg".equals(kind) ? positive(lower) : rate;
        BigDecimal hi = "peg".equals(kind) ? positive(upper) : rate;
        if (lo.compareTo(rate) > 0 || rate.compareTo(hi) > 0) {
            throw new IllegalArgumentException("目标汇率必须处于下限和上限之间。");
        }
        return "note-policy " + kind + " " + token(rate) + " " + token(lo) + " " + token(hi);
    }
    private static BigDecimal positive(String text) {
        if (text == null || !text.matches("[0-9]{1,16}(\\.[0-9]{1,4})?")) {
            throw new IllegalArgumentException("汇率须为正数，最多 4 位小数。");
        }
        BigDecimal value = new BigDecimal(text);
        if (value.signum() <= 0) throw new IllegalArgumentException("汇率必须大于零。");
        return value;
    }
    private static String token(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}
