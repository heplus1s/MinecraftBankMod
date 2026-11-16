package dev.jdguo.bank.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Wraps by measured width, including Chinese text that has no spaces. */
public final class BankText {
    private BankText() {
    }

    public static List<String> lines(String text, int maxWidth, ToIntFunction<String> measure) {
        List<String> lines = new ArrayList<>();
        String rest = text == null ? "" : text;
        if (rest.isEmpty() || maxWidth <= 0) {
            lines.add(rest);
            return lines;
        }
        while (!rest.isEmpty()) {
            String part = rest;
            while (measure.applyAsInt(part) > maxWidth && part.codePointCount(0, part.length()) > 1) {
                part = part.substring(0, part.offsetByCodePoints(0, part.codePointCount(0, part.length()) - 1));
            }
            if (part.isEmpty()) {
                part = rest.substring(0, rest.offsetByCodePoints(0, 1));
            }
            lines.add(part);
            rest = rest.substring(part.length());
        }
        return lines;
    }
}
