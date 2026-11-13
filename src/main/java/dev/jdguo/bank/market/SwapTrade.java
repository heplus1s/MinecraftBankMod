package dev.jdguo.bank.market;

import java.math.BigInteger;

/** One cleared swap of two personal notes. Reserves are not part of this result. */
public record SwapTrade(boolean success, String code, PersonalNote paid, PersonalNote received,
                        BigInteger paidNotes, BigInteger receivedNotes, BigInteger central, BigInteger copper) {
    public static SwapTrade ok(PersonalNote paid, PersonalNote received, BigInteger paidNotes,
                               BigInteger receivedNotes, BigInteger central, BigInteger copper) {
        return new SwapTrade(true, "ok", paid, received,
                paidNotes == null ? BigInteger.ZERO : paidNotes,
                receivedNotes == null ? BigInteger.ZERO : receivedNotes,
                central == null ? BigInteger.ZERO : central,
                copper == null ? BigInteger.ZERO : copper);
    }

    public static SwapTrade fail(String code) {
        return new SwapTrade(false, code, null, null, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
    }
}
