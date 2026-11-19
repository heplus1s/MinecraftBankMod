package dev.jdguo.bank.net;

import java.math.BigDecimal;

/** Strict wire grammar. Ledger independently validates permissions, currency and policy revision. */
public record WelcomeGiftAction(boolean claim, long revision, boolean enabled, String currency, BigDecimal amount) {
    public static WelcomeGiftAction parse(String[] parts) {
        if (parts == null || parts.length < 2 || !parts[1].matches("[0-9]{1,19}")) throw new IllegalArgumentException("revision");
        long revision = Long.parseLong(parts[1]);
        if ("welcome-claim".equals(parts[0]) && parts.length == 2)
            return new WelcomeGiftAction(true, revision, false, "", BigDecimal.ZERO);
        if (!"admin-welcome-set".equals(parts[0]) || parts.length != 5
                || !("true".equals(parts[2]) || "false".equals(parts[2]))
                || !parts[3].matches("[0-9]{1,24}(\\.[0-9]{1,4})?")
                || parts[4].isBlank() || parts[4].length() > 100)
            throw new IllegalArgumentException("arguments");
        BigDecimal amount = new BigDecimal(parts[3]);
        if (amount.signum() <= 0) throw new IllegalArgumentException("amount");
        return new WelcomeGiftAction(false, revision, Boolean.parseBoolean(parts[2]), parts[4], amount);
    }
}
