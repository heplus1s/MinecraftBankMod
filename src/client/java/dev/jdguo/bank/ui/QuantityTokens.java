package dev.jdguo.bank.ui;

/** Turns the amount box into the token the server already understands. */
public final class QuantityTokens {
    private QuantityTokens() {
    }

    public static String token(String raw) {
        if (raw == null || raw.isBlank()) {
            return "1";
        }
        String trimmed = raw.trim();
        if ("全部".equals(trimmed) || "all".equalsIgnoreCase(trimmed) || "max".equalsIgnoreCase(trimmed)) {
            return "all";
        }
        return trimmed;
    }
}
