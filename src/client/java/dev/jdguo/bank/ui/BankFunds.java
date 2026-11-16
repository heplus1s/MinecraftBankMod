package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;

import java.math.BigDecimal;

/** Client-side balance check. Missing balances are not treated as zero. */
public final class BankFunds {
    private BankFunds() {
    }

    public static String withdrawalRefusal(JsonObject data, String currencyId, String currencyName, String quantity) {
        return refusal(data, currencyId, currencyName, quantity, true);
    }

    /** Copper and diamond exchanges spend a physical balance. Notes are left to the core. */
    public static String physicalExchangeRefusal(JsonObject data, String payId, String payName, String quantity) {
        if (!physical(payId, payName)) {
            return null;
        }
        return refusal(data, payId, payName, quantity, false);
    }

    private static String refusal(JsonObject data, String currencyId, String currencyName, String quantity, boolean withdrawal) {
        if (quantity == null || "all".equalsIgnoreCase(quantity.trim()) || "全部".equals(quantity.trim())) {
            return null;
        }
        BigDecimal need = BankRates.parse(quantity);
        String held = held(data, currencyId);
        BigDecimal have = BankRates.parse(held);
        if (need == null || have == null || need.compareTo(have) <= 0) {
            return null;
        }
        String name = currencyName == null || currencyName.isBlank() ? "所选货币" : currencyName;
        String needed = BankHeader.figure(need.toPlainString());
        String owned = BankHeader.figure(have.toPlainString());
        if (withdrawal) {
            return "余额不足。要取出 " + needed + " " + name + "，账户里只有 " + owned + " " + name + "。";
        }
        return "余额不足。这次兑换要付出 " + needed + " " + name + "，账户里只有 " + owned + " " + name + "，不能完成这笔兑换。";
    }

    private static boolean physical(String id, String name) {
        return "copper".equals(id) || "diamond".equals(id) || "铜锭".equals(name) || "钻石".equals(name);
    }

    private static String held(JsonObject data, String currencyId) {
        for (BankSnapshot.MoneyRow row : BankSnapshot.currencies(data)) {
            if (currencyId != null && currencyId.equals(row.id())) {
                return row.amount();
            }
        }
        return null;
    }
}
