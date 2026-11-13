package dev.jdguo.bank.ledger;

import java.math.BigInteger;

/**
 * Two asset pools for a commodity, plus the public book.
 * {@code reserve} is the issuance reserve (发行准备金). {@code depositVault} is savings custody
 * (储蓄保管) and is not spent by convertibility or FX. {@code locked} encumbers only the issuance reserve.
 * For the central note, {@code reserve} is the FX seed plus reserve-backed issuance, {@code liability}
 * is public M1, and {@code anchor} is the opening seed used by the peg — not public money.
 */
public final class Supply {
    private final String currency;
    private BigInteger reserve;
    private BigInteger liability;
    private BigInteger anchor;
    private BigInteger locked;
    private BigInteger depositVault = BigInteger.ZERO;

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
    public BigInteger depositVault() { return depositVault; }

    public BigInteger freeReserve() {
        BigInteger free = reserve.subtract(locked);
        return free.signum() < 0 ? BigInteger.ZERO : free;
    }

    void setReserve(BigInteger reserve) { this.reserve = reserve; }
    void setLiability(BigInteger liability) { this.liability = liability; }
    void setAnchor(BigInteger anchor) { this.anchor = anchor; }
    void setLocked(BigInteger locked) { this.locked = locked; }

    void addReserve(BigInteger delta) { this.reserve = this.reserve.add(delta); }
    void addLiability(BigInteger delta) { this.liability = this.liability.add(delta); }
    void addLocked(BigInteger delta) { this.locked = this.locked.add(delta); }
    void setDepositVault(BigInteger depositVault) { this.depositVault = depositVault; }
    void addDepositVault(BigInteger delta) { this.depositVault = this.depositVault.add(delta); }
}
