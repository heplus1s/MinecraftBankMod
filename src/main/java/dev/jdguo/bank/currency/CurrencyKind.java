package dev.jdguo.bank.currency;

public enum CurrencyKind {
    /** Backed by a vanilla item players can deposit and withdraw. */
    COMMODITY,
    /** Account money and checks. No raw item deposit. */
    VIRTUAL
}
