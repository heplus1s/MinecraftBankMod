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
    private final boolean copperPar;
    private final BigInteger par;
    private BigInteger circulation = BigInteger.ZERO;
    private BigInteger buys = BigInteger.ZERO;
    private BigInteger sells = BigInteger.ZERO;
    private BigInteger locked = BigInteger.ZERO;

    public PersonalNote(UUID id, UUID issuer, String name, boolean copperPar, BigInteger par) {
        this.id = id;
        this.issuer = issuer;
        this.name = name;
        this.copperPar = copperPar;
        this.par = par;
    }

    public UUID id() { return id; }
    public UUID issuer() { return issuer; }
    public String name() { return name; }
    public boolean copperPar() { return copperPar; }
    public BigInteger par() { return par; }
    public BigInteger circulation() { return circulation; }
    public BigInteger buys() { return buys; }
    public BigInteger sells() { return sells; }
    public BigInteger locked() { return locked; }

    public void setCirculation(BigInteger circulation) { this.circulation = circulation; }
    public void setBuys(BigInteger buys) { this.buys = buys; }
    public void setSells(BigInteger sells) { this.sells = sells; }
    public void setLocked(BigInteger locked) { this.locked = locked; }

    public void addBuy(BigInteger amount) { this.buys = this.buys.add(amount); }
    public void addSell(BigInteger amount) { this.sells = this.sells.add(amount); }
}
