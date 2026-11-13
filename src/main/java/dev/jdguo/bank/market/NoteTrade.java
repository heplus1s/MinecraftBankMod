package dev.jdguo.bank.market;

import java.math.BigInteger;

/** Outcome of creating, issuing, or trading a personal note. */
public record NoteTrade(boolean success, String code, PersonalNote note, BigInteger centralPaid, BigInteger copperPaid) {
    public static NoteTrade ok(PersonalNote note, BigInteger centralPaid, BigInteger copperPaid) {
        return new NoteTrade(true, "ok", note, centralPaid == null ? BigInteger.ZERO : centralPaid,
                copperPaid == null ? BigInteger.ZERO : copperPaid);
    }

    public static NoteTrade fail(String code) {
        return new NoteTrade(false, code, null, BigInteger.ZERO, BigInteger.ZERO);
    }
}
