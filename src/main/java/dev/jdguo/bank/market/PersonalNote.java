package dev.jdguo.bank.market;

import java.math.BigInteger;
import java.util.UUID;

/**
 * One player's own bank note. Holdings are not savings, and the locked figure is not the reserve itself.
 */
public final class PersonalNote {
    private final UUID id;
    private final UUID issuer;
    private final String name;
    private final NoteRegime regime;
    /** Reserve and payout currency: diamond, copper, or note. */
    private final String anchor;
    private final BigInteger par;
    private BigInteger circulation = BigInteger.ZERO;
    private BigInteger buys = BigInteger.ZERO;
    private BigInteger sells = BigInteger.ZERO;
    private BigInteger locked = BigInteger.ZERO;
    /** Reserve units already paid out by redemption. Counted into bankruptcy debt. */
    private BigInteger drawn = BigInteger.ZERO;
    /** Collateral taken by the central bank at bankruptcy. Not returned to the issuer. */
    private BigInteger released = BigInteger.ZERO;
    private boolean bankrupt;
    private long closedAtEpochMs;

    /** Floating note. {@code copperPar} chooses whether par is copper or diamonds. */
    public PersonalNote(UUID id, UUID issuer, String name, boolean copperPar, BigInteger par) {
        this(id, issuer, name, NoteRegime.FLOAT, copperPar ? "copper" : "diamond", par);
    }

    public PersonalNote(UUID id, UUID issuer, String name, NoteRegime regime, String anchor, BigInteger par) {
        this.id = id;
        this.issuer = issuer;
        this.name = name;
        this.regime = regime == null ? NoteRegime.FLOAT : regime;
        this.anchor = anchor == null || anchor.isBlank() ? "diamond" : anchor;
        this.par = par;
    }

    public UUID id() { return id; }
    public UUID issuer() { return issuer; }
    public String name() { return name; }
    public NoteRegime regime() { return regime; }
    public String anchor() { return anchor; }
    /** Reserve currency the lock is posted in. */
    public String reserveCurrency() { return anchor; }
    public boolean copperPar() { return "copper".equals(anchor); }
    public BigInteger par() { return par; }
    public BigInteger circulation() { return circulation; }
    public BigInteger buys() { return buys; }
    public BigInteger sells() { return sells; }
    public BigInteger locked() { return locked; }
    public BigInteger drawn() { return drawn; }
    public BigInteger released() { return released; }
    public boolean bankrupt() { return bankrupt; }
    public long closedAtEpochMs() { return closedAtEpochMs; }

    public void addDrawn(BigInteger amount) {
        if (amount != null && amount.signum() > 0) {
            this.drawn = this.drawn.add(amount);
        }
    }

    /** Voids the note. {@code seized} is collateral taken by the bank, not a refund. */
    public void declareBankrupt(long when, BigInteger seized) {
        if (bankrupt) {
            return;
        }
        this.released = seized == null ? BigInteger.ZERO : seized;
        this.locked = BigInteger.ZERO;
        this.bankrupt = true;
        this.closedAtEpochMs = when;
    }

    public void restoreClosure(boolean bankrupt, long closedAtEpochMs, BigInteger released, BigInteger drawn) {
        this.bankrupt = bankrupt;
        this.closedAtEpochMs = closedAtEpochMs;
        this.released = released == null ? BigInteger.ZERO : released;
        this.drawn = drawn == null ? BigInteger.ZERO : drawn;
    }

    public void setCirculation(BigInteger circulation) { this.circulation = circulation; }
    public void setBuys(BigInteger buys) { this.buys = buys; }
    public void setSells(BigInteger sells) { this.sells = sells; }
    public void setLocked(BigInteger locked) { this.locked = locked; }

    public void addBuy(BigInteger amount) { this.buys = this.buys.add(amount); }
    public void addSell(BigInteger amount) { this.sells = this.sells.add(amount); }
}
