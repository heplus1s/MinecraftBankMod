package dev.jdguo.bank.instruments;

import java.math.BigInteger;
import java.util.UUID;

/**
 * What the server read off a vanilla paper note. {@code personalNote} is null for a central bank note.
 * Face value is per sheet, so a stack's worth is {@code face × count}.
 */
public record PresentedNote(String currency, UUID personalNote, BigInteger face) {
    public boolean central() {
        return personalNote == null;
    }
}
