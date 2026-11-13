package dev.jdguo.bank.instruments;

import java.math.BigInteger;
import java.util.UUID;

/**
 * Fungible paper series. Every sheet of the same series carries the same NBT, so vanilla paper stacks
 * up to 64. The ledger counts sheets still out, not one id per sheet, which is what makes stacking possible.
 * A split or a merge does not change that count. A copy can be deposited only while sheets remain.
 */
public final class NoteSeries {
    private NoteSeries() {}

    public static String key(PresentedNote note) {
        return key(note.currency(), note.personalNote(), note.face());
    }

    public static String key(String currency, UUID personalNote, BigInteger face) {
        if (face == null || face.signum() <= 0) {
            throw new IllegalArgumentException("face");
        }
        if (personalNote != null) {
            return "p/" + personalNote + "/" + face.toString();
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency");
        }
        return "c/" + currency + "/" + face.toString();
    }

    /**
     * Unit sheets whenever they fit in one withdrawal, so 64 notes are one pile of paper.
     * A larger amount is one sheet of that face; repeating the same amount stacks.
     */
    public static PaperSlip plan(BigInteger amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount");
        }
        if (amount.compareTo(dev.jdguo.bank.ledger.BankBooks.MAX_PHYSICAL) <= 0) {
            return new PaperSlip(BigInteger.ONE, amount);
        }
        return new PaperSlip(amount, BigInteger.ONE);
    }

    /** Display identity. Same series and label stack; central notes never share this with a personal note. */
    public static String identity(String currency, UUID personalNote, BigInteger face, String label) {
        if (personalNote != null) {
            return "personal\n" + personalNote + "\n" + face.toString() + "\n" + label;
        }
        return "central\n" + currency + "\n" + face.toString() + "\n" + label;
    }

    public record PaperSlip(BigInteger face, BigInteger sheets) {
        public BigInteger value() {
            return face.multiply(sheets);
        }
    }
}
