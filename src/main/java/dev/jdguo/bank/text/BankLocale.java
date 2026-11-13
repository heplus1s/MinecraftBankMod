package dev.jdguo.bank.text;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client language captured from the vanilla settings packet.
 * Missing or non-English languages use Chinese, which is the default voice.
 */
public final class BankLocale {
    private static final Map<UUID, String> LANGUAGE = new ConcurrentHashMap<>();

    private BankLocale() {}

    public static void remember(UUID player, String language) {
        if (player == null || language == null || language.isBlank()) {
            return;
        }
        LANGUAGE.put(player, language);
    }

    public static boolean english(UUID player) {
        if (player == null) {
            return false;
        }
        String language = LANGUAGE.get(player);
        if (language == null) {
            return false;
        }
        String code = language.toLowerCase(Locale.ROOT).replace('-', '_');
        return code.equals("en") || code.startsWith("en_");
    }

    static void clear() {
        LANGUAGE.clear();
    }
}
