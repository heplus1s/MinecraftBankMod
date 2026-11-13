package dev.jdguo.bank.market;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns commodity stacks into repayment of a central-bank debt.
 * Same-currency items pay that debt first. Copper and diamonds cross at the live whole rate.
 * A diamond is taken for a copper debt only when the debt covers a whole diamond.
 *
 * <p>This class prices only registered commodity currencies. Arbitrary backpack items
 * wait for {@link ShopQuote}; do not add a shop price list here.
 */
public final class DebtSeizure {
    /** Once a second at 20 ticks. */
    public static final int INTERVAL_TICKS = 20;

    private DebtSeizure() {}

    public static boolean due(int tick) {
        return tick > 0 && tick % INTERVAL_TICKS == 0;
    }

    public static Map<String, BigInteger> plan(Map<String, BigInteger> debt, Map<String, BigInteger> available,
                                               BigInteger copperPerDiamond) {
        Map<String, BigInteger> owed = new LinkedHashMap<>();
        if (debt != null) {
            debt.forEach((currency, amount) -> {
                if (amount != null && amount.signum() > 0) {
                    owed.put(currency, amount);
                }
            });
        }
        Map<String, BigInteger> have = new LinkedHashMap<>();
        if (available != null) {
            available.forEach((currency, amount) -> {
                if (amount != null && amount.signum() > 0) {
                    have.put(currency, amount);
                }
            });
        }
        Map<String, BigInteger> take = new LinkedHashMap<>();
        for (String currency : new java.util.ArrayList<>(owed.keySet())) {
            BigInteger use = min(owed.get(currency), have.getOrDefault(currency, BigInteger.ZERO));
            if (use.signum() <= 0) {
                continue;
            }
            take.merge(currency, use, BigInteger::add);
            owed.put(currency, owed.get(currency).subtract(use));
            have.put(currency, have.get(currency).subtract(use));
        }
        BigInteger rate = copperPerDiamond == null || copperPerDiamond.signum() <= 0
                ? BigInteger.valueOf(1000) : copperPerDiamond;
        applyCross(owed, have, take, "diamond", "copper", rate);
        applyCross(owed, have, take, "copper", "diamond", rate);
        return take;
    }

    /** {@code payCurrency} items repay {@code debtCurrency}. Rate is copper per diamond. */
    private static void applyCross(Map<String, BigInteger> owed, Map<String, BigInteger> have,
                                   Map<String, BigInteger> take, String debtCurrency, String payCurrency,
                                   BigInteger copperPerDiamond) {
        BigInteger debt = owed.getOrDefault(debtCurrency, BigInteger.ZERO);
        BigInteger pile = have.getOrDefault(payCurrency, BigInteger.ZERO);
        if (debt.signum() <= 0 || pile.signum() <= 0) {
            return;
        }
        BigInteger use;
        BigInteger repay;
        if ("diamond".equals(debtCurrency)) {
            repay = debt.min(pile.divide(copperPerDiamond));
            use = repay.multiply(copperPerDiamond);
        } else {
            use = debt.divide(copperPerDiamond).min(pile);
            repay = use.multiply(copperPerDiamond);
        }
        if (use.signum() <= 0 || repay.signum() <= 0) {
            return;
        }
        take.merge(payCurrency, use, BigInteger::add);
        owed.put(debtCurrency, debt.subtract(repay));
        have.put(payCurrency, pile.subtract(use));
    }

    /** Debt left after {@code taken} commodities are applied with the same rules as {@link #plan}. */
    public static Map<String, BigInteger> settle(Map<String, BigInteger> debt, Map<String, BigInteger> taken,
                                                 BigInteger copperPerDiamond) {
        Map<String, BigInteger> owed = new LinkedHashMap<>();
        if (debt != null) {
            debt.forEach((currency, amount) -> {
                if (amount != null && amount.signum() > 0) {
                    owed.put(currency, amount);
                }
            });
        }
        Map<String, BigInteger> pile = new LinkedHashMap<>();
        if (taken != null) {
            taken.forEach((currency, amount) -> {
                if (amount != null && amount.signum() > 0) {
                    pile.put(currency, amount);
                }
            });
        }
        for (String currency : new java.util.ArrayList<>(owed.keySet())) {
            BigInteger use = min(owed.get(currency), pile.getOrDefault(currency, BigInteger.ZERO));
            if (use.signum() <= 0) {
                continue;
            }
            owed.put(currency, owed.get(currency).subtract(use));
            pile.put(currency, pile.get(currency).subtract(use));
        }
        BigInteger rate = copperPerDiamond == null || copperPerDiamond.signum() <= 0
                ? BigInteger.valueOf(1000) : copperPerDiamond;
        settleCross(owed, pile, "diamond", "copper", rate);
        settleCross(owed, pile, "copper", "diamond", rate);
        owed.entrySet().removeIf(entry -> entry.getValue().signum() <= 0);
        return owed;
    }

    private static void settleCross(Map<String, BigInteger> owed, Map<String, BigInteger> pile,
                                    String debtCurrency, String payCurrency, BigInteger copperPerDiamond) {
        BigInteger debt = owed.getOrDefault(debtCurrency, BigInteger.ZERO);
        BigInteger have = pile.getOrDefault(payCurrency, BigInteger.ZERO);
        if (debt.signum() <= 0 || have.signum() <= 0) {
            return;
        }
        BigInteger repay = "diamond".equals(debtCurrency)
                ? debt.min(have.divide(copperPerDiamond))
                : have.min(debt.divide(copperPerDiamond)).multiply(copperPerDiamond);
        if (repay.signum() <= 0) {
            return;
        }
        owed.put(debtCurrency, debt.subtract(repay));
        BigInteger use = "diamond".equals(debtCurrency) ? repay.multiply(copperPerDiamond) : repay.divide(copperPerDiamond);
        pile.put(payCurrency, have.subtract(use));
    }

    private static BigInteger min(BigInteger left, BigInteger right) {
        return left.compareTo(right) <= 0 ? left : right;
    }
}
