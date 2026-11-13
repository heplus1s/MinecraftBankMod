package dev.jdguo.bank.market;

import dev.jdguo.bank.fx.FxMath;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Price of a personal note in diamonds.
 *
 * <p>M1 is notes in circulation. Backing is the issuer's personal reserve still posted
 * against the note, measured in the par currency (diamond or copper). Strength is that
 * backing divided by the par promise ({@code par * M1}). Buy volume minus sell volume,
 * scaled by M1, nudges the price. The weight is {@link #FLOW_WEIGHT}. The nudge is
 * kept between {@link #FLOW_MIN} and {@link #FLOW_MAX}.
 *
 * <p>{@code P = P0 * strength * flow}, where {@code P0} is the par converted into diamonds
 * at the live copper-per-diamond rate (1,000 when the central books have just opened).
 */
public final class PersonalMarket {
    public static final BigDecimal FLOW_WEIGHT = new BigDecimal("0.1");
    public static final BigDecimal FLOW_MIN = new BigDecimal("0.25");
    public static final BigDecimal FLOW_MAX = new BigDecimal("4");

    private PersonalMarket() {}

    /** Up to three Han characters or English letters taken from a player name, for the chest buttons. */
    public static String shortName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        raw.codePoints().forEach(cp -> {
            if (sb.codePointCount(0, sb.length()) >= 3) {
                return;
            }
            boolean letter = (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
            boolean han = Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
            if (letter || han) {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    public static boolean validName(String name) {
        if (name == null) {
            return false;
        }
        int count = name.codePointCount(0, name.length());
        if (count < 1 || count > 3) {
            return false;
        }
        for (int cp : name.codePoints().toArray()) {
            boolean letter = (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
            boolean han = Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
            if (!letter && !han) {
                return false;
            }
        }
        return true;
    }

    public static String canonicalName(String name) {
        StringBuilder sb = new StringBuilder();
        name.codePoints().forEach(cp -> {
            if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')) {
                sb.appendCodePoint(Character.toLowerCase(cp));
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    public static BigDecimal diamondPrice(PersonalNote note, BigInteger copperPerDiamond, BigInteger effectiveBacking) {
        BigDecimal p0 = note.copperPar()
                ? new BigDecimal(note.par()).divide(new BigDecimal(copperPerDiamond), FxMath.MC)
                : new BigDecimal(note.par());
        if (note.circulation().signum() <= 0) {
            return p0;
        }
        BigDecimal circ = new BigDecimal(note.circulation());
        BigDecimal promised = new BigDecimal(note.par()).multiply(circ, FxMath.MC);
        BigDecimal strength = promised.signum() == 0
                ? BigDecimal.ZERO
                : new BigDecimal(effectiveBacking.max(BigInteger.ZERO)).divide(promised, FxMath.MC);
        BigDecimal flow = new BigDecimal(note.buys().subtract(note.sells())).divide(circ, FxMath.MC);
        BigDecimal factor = BigDecimal.ONE.add(FLOW_WEIGHT.multiply(flow, FxMath.MC), FxMath.MC);
        if (factor.compareTo(FLOW_MIN) < 0) {
            factor = FLOW_MIN;
        }
        if (factor.compareTo(FLOW_MAX) > 0) {
            factor = FLOW_MAX;
        }
        BigDecimal price = p0.multiply(strength, FxMath.MC).multiply(factor, FxMath.MC);
        return price.signum() < 0 ? BigDecimal.ZERO : price;
    }

    /** Note-units of backing. This plus M1 is M2. */
    public static BigDecimal reserveNotes(PersonalNote note, BigInteger effectiveBacking) {
        if (note.par().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(effectiveBacking.max(BigInteger.ZERO)).divide(new BigDecimal(note.par()), FxMath.MC);
    }
}
