package dev.jdguo.bank.ui;

import java.util.HashMap;
import java.util.Map;

/** Screen-local drafts: navigating to another operation must not reuse its amount. */
final class AmountDrafts {
    private final Map<String, String> drafts = new HashMap<>();
    private String context;

    String select(String next) {
        context = next;
        return drafts.getOrDefault(next, "1");
    }

    void remember(String text) {
        if (context != null) drafts.put(context, text == null ? "" : text);
    }

    static boolean isPhysicalInput(String text) {
        return text != null && text.matches("[0-9]{0,9}");
    }

    static String physicalInput(String text) {
        return isPhysicalInput(text) ? text : "1";
    }
}
