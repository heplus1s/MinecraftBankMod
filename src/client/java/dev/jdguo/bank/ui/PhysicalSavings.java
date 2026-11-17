package dev.jdguo.bank.ui;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Display only: never mutates or rounds away stored physical-account residue. */
public final class PhysicalSavings {
    private PhysicalSavings() {}
    public static String describe(String amount) {
        try {
            BigDecimal balance = new BigDecimal(amount.replace(",", ""));
            BigDecimal whole = balance.setScale(0, RoundingMode.DOWN);
            BigDecimal residue = balance.subtract(whole);
            return "可取实物：" + whole.toPlainString() + " 件"
                    + (residue.signum() == 0 ? "" : "；零散账面权益：" + residue.stripTrailingZeros().toPlainString() + "（保留，不作为物品拆分）");
        } catch (RuntimeException invalid) {
            return "可取实物：" + amount + " 件";
        }
    }
}
