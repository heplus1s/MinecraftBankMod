package dev.jdguo.bank.market;

import java.math.BigInteger;

/** Universal redemption of a personal note at the live quote, paid from that note's posted reserve. */
public record Redemption(boolean success, String code, PersonalNote note, BigInteger retired,
                         BigInteger diamonds, BigInteger copper, BigInteger central, boolean discounted,
                         BigInteger sheetsTaken) {
    public Redemption {
        if (sheetsTaken == null) {
            sheetsTaken = BigInteger.ZERO;
        }
    }

    public static Redemption ok(PersonalNote note, BigInteger retired, BigInteger diamonds, BigInteger copper,
                                BigInteger central, boolean discounted) {
        return new Redemption(true, "ok", note,
                retired == null ? BigInteger.ZERO : retired,
                diamonds == null ? BigInteger.ZERO : diamonds,
                copper == null ? BigInteger.ZERO : copper,
                central == null ? BigInteger.ZERO : central,
                discounted,
                BigInteger.ZERO);
    }

    public static Redemption fail(String code) {
        return new Redemption(false, code, null, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, false, BigInteger.ZERO);
    }
}
