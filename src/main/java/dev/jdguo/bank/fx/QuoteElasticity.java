package dev.jdguo.bank.fx;

import dev.jdguo.bank.ledger.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Slow quote movement. A transaction shifts the live quote by at most one percent.
 *
 * <p>The raw shift is {@code quote * 0.25 * signedVolume / reference}. {@code reference} is the
 * opening stock of the currency being priced, so one small redemption against a 1,000-diamond
 * book moves a 1,000 copper-per-diamond quote by 0.25, not by a doubling. Volume past that cap
 * still moves the quote, but only by the one-percent ceiling. Nothing here pulls a quote back
 * to par on its own; the next posting has to push it.
 */
public final class QuoteElasticity {
    public static final BigDecimal ELASTICITY = new BigDecimal("0.25");
    public static final BigDecimal CAP = new BigDecimal("0.01");

    private QuoteElasticity() {}

    /**
     * @param signedVolume positive when the quoted thing is in greater demand
     * @return the quote unchanged when it is missing; otherwise a positive four-decimal quote
     */
    public static BigDecimal nudge(BigDecimal quote, BigDecimal signedVolume, BigDecimal reference) {
        if (quote == null || quote.signum() <= 0) {
            return quote;
        }
        if (signedVolume == null || signedVolume.signum() == 0) {
            return Money.of(quote);
        }
        BigDecimal base = reference == null || reference.signum() <= 0 ? BigDecimal.ONE : reference;
        BigDecimal raw = quote.multiply(ELASTICITY).multiply(signedVolume)
                .divide(base, 8, RoundingMode.HALF_UP);
        BigDecimal capAbs = quote.multiply(CAP);
        if (raw.compareTo(capAbs) > 0) {
            raw = capAbs;
        } else if (raw.compareTo(capAbs.negate()) < 0) {
            raw = capAbs.negate();
        }
        BigDecimal next = Money.of(quote.add(raw));
        return next.signum() > 0 ? next : Money.UNIT;
    }
}
