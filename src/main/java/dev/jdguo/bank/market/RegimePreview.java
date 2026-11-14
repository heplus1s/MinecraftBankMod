package dev.jdguo.bank.market;

/**
 * One possible regime switch. {@code reason} is a complete Chinese sentence.
 * {@code applied} is true only after the books have posted the switch.
 */
public record RegimePreview(NoteRegime regime, String anchor, boolean allowed, boolean applied, String reason) {
    public RegimePreview {
        anchor = anchor == null ? "" : anchor;
        reason = reason == null ? "" : reason;
    }
}
