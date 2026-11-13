package dev.jdguo.bank.ledger;

import java.math.BigInteger;

/** Reserve, liability, opening anchor, and encumbered (locked) units of one currency. */
public final class Supply {
    private final String currency;
    private BigInteger reserve;
    private BigInteger liability;
    private BigInteger anchor;
    private BigInteger locked;

    public Supply(String currency, BigInteger reserve, BigInteger liability, BigInteger anchor, BigInteger locked) {
        this.currency = currency;
        this.reserve = reserve;
        this.liability = liability;
        this.anchor = anchor;
        this.locked = locked;
    }

    public String currency() { return currency; }
    public BigInteger reserve() { return reserve; }
    public BigInteger liability() { return liability; }
    public BigInteger anchor() { return anchor; }
    public BigInteger locked() { return locked; }

    public BigInteger freeReserve() {
        BigInteger free = reserve.subtract(locked);
        return free.signum() < 0 ? BigInteger.ZERO : free;
    }

    void setReserve(BigInteger reserve) { this.reserve = reserve; }
    void setLiability(BigInteger liability) { this.liability = liability; }
    void setLocked(BigInteger locked) { this.locked = locked; }

    void addReserve(BigInteger delta) { this.reserve = this.reserve.add(delta); }
    void addLiability(BigInteger delta) { this.liability = this.liability.add(delta); }
    void addLocked(BigInteger delta) { this.locked = this.locked.add(delta); }
}
