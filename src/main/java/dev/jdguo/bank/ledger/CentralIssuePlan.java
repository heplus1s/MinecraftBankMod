package dev.jdguo.bank.ledger;

import java.math.BigDecimal;
import java.math.BigInteger;

/** A quote, never a posting. Target units are diamonds per one central note. */
public record CentralIssuePlan(boolean allowed, String reason, BigDecimal target,
                               BigInteger amount, BigDecimal resultingRate,
                               BigDecimal reserveValue, BigDecimal liabilitiesAfter,
                               String fingerprint) {
    public static boolean sameTarget(String entered, String quoted) {
        try { return entered != null && quoted != null && new BigDecimal(entered).compareTo(new BigDecimal(quoted)) == 0; }
        catch (NumberFormatException invalid) { return false; }
    }
}
