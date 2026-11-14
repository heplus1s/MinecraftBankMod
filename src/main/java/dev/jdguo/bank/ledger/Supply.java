package dev.jdguo.bank.ledger;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Two asset pools for a commodity, plus the public book.
 * {@code reserve} is the issuance reserve (发行准备金). {@code depositVault} is savings custody
 * (储蓄保管) and is not spent by convertibility or FX. {@code locked} encumbers only the issuance reserve.
 * For the central note, {@code reserve} is the FX seed plus reserve-backed issuance, {@code liability}
 * is public M1, and {@code anchor} is the opening seed used by the peg — not public money.
 *
 * <p>Amounts are stored at four decimal places. The {@link BigInteger} views are the whole units
 * toward zero, so an integral balance still reads as that integer.
 */
public final class Supply {
    private final String currency;
    private BigDecimal reserve;
    private BigDecimal liability;
    private BigDecimal anchor;
    private BigDecimal locked;
    private BigDecimal depositVault = Money.ZERO;

    public Supply(String currency, BigInteger reserve, BigInteger liability, BigInteger anchor, BigInteger locked) {
        this(currency, Money.of(reserve), Money.of(liability), Money.of(anchor), Money.of(locked));
    }

    Supply(String currency, BigDecimal reserve, BigDecimal liability, BigDecimal anchor, BigDecimal locked) {
        this.currency = currency;
        this.reserve = Money.of(reserve);
        this.liability = Money.of(liability);
        this.anchor = Money.of(anchor);
        this.locked = Money.of(locked);
    }

    public String currency() { return currency; }
    public BigInteger reserve() { return Money.units(reserve); }
    public BigInteger liability() { return Money.units(liability); }
    public BigInteger anchor() { return Money.units(anchor); }
    public BigInteger locked() { return Money.units(locked); }
    public BigInteger depositVault() { return Money.units(depositVault); }

    public BigDecimal reserveExact() { return reserve; }
    public BigDecimal liabilityExact() { return liability; }
    public BigDecimal anchorExact() { return anchor; }
    public BigDecimal lockedExact() { return locked; }
    public BigDecimal depositVaultExact() { return depositVault; }

    public BigInteger freeReserve() {
        BigDecimal free = reserve.subtract(locked);
        return free.signum() < 0 ? BigInteger.ZERO : Money.units(free);
    }

    BigDecimal freeReserveExact() {
        BigDecimal free = reserve.subtract(locked);
        return free.signum() < 0 ? Money.ZERO : free;
    }

    void setReserve(BigInteger reserve) { this.reserve = Money.of(reserve); }
    void setLiability(BigInteger liability) { this.liability = Money.of(liability); }
    void setAnchor(BigInteger anchor) { this.anchor = Money.of(anchor); }
    void setLocked(BigInteger locked) { this.locked = Money.of(locked); }
    void setReserve(BigDecimal reserve) { this.reserve = Money.of(reserve); }
    void setLiability(BigDecimal liability) { this.liability = Money.of(liability); }
    void setDepositVault(BigDecimal depositVault) { this.depositVault = Money.of(depositVault); }

    void addReserve(BigInteger delta) { addReserve(Money.of(delta)); }
    void addLiability(BigInteger delta) { addLiability(Money.of(delta)); }
    void addLocked(BigInteger delta) { addLocked(Money.of(delta)); }
    void setDepositVault(BigInteger depositVault) { this.depositVault = Money.of(depositVault); }
    void addDepositVault(BigInteger delta) { addDepositVault(Money.of(delta)); }

    void addReserve(BigDecimal delta) { this.reserve = Money.of(this.reserve.add(Money.of(delta))); }
    void addLiability(BigDecimal delta) { this.liability = Money.of(this.liability.add(Money.of(delta))); }
    void addLocked(BigDecimal delta) { this.locked = Money.of(this.locked.add(Money.of(delta))); }
    void addDepositVault(BigDecimal delta) { this.depositVault = Money.of(this.depositVault.add(Money.of(delta))); }
}
