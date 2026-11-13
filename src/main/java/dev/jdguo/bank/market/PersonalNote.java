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
    /** Lock released when the note was declared bankrupt. Zero while the note is still live. */
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
    public BigInteger released() { return released; }
    public boolean bankrupt() { return bankrupt; }
    public long closedAtEpochMs() { return closedAtEpochMs; }

    /** Voids the note. Circulation, trades, and the released lock stay on the record. */
    public void declareBankrupt(long when) {
        if (bankrupt) {
            return;
        }
        this.released = locked;
        this.locked = BigInteger.ZERO;
        this.bankrupt = true;
        this.closedAtEpochMs = when;
    }

    public void restoreClosure(boolean bankrupt, long closedAtEpochMs, BigInteger released) {
        this.bankrupt = bankrupt;
        this.closedAtEpochMs = closedAtEpochMs;
        this.released = released == null ? BigInteger.ZERO : released;
    }

    public void setCirculation(BigInteger circulation) { this.circulation = circulation; }
    public void setBuys(BigInteger buys) { this.buys = buys; }
    public void setSells(BigInteger sells) { this.sells = sells; }
    public void setLocked(BigInteger locked) { this.locked = locked; }

    public void addBuy(BigInteger amount) { this.buys = this.buys.add(amount); }
    public void addSell(BigInteger amount) { this.sells = this.sells.add(amount); }
}
