package dev.jdguo.bank.instruments;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

public final class CheckRecord {
    private final UUID id;
    private final String currency;
    private final BigInteger amount;
    private final UUID issuer;
    private final String issuerName;
    private final UUID payee;
    private final long issuedAtEpochMs;
    private CheckStatus status;
    private UUID redeemedBy;
    private long redeemedAtEpochMs;

    public CheckRecord(UUID id, String currency, BigInteger amount, UUID issuer, String issuerName, UUID payee,
                       long issuedAtEpochMs, CheckStatus status, UUID redeemedBy, long redeemedAtEpochMs) {
        this.id = id;
        this.currency = currency;
        this.amount = amount;
        this.issuer = issuer;
        this.issuerName = issuerName;
        this.payee = payee;
        this.issuedAtEpochMs = issuedAtEpochMs;
        this.status = status;
        this.redeemedBy = redeemedBy;
        this.redeemedAtEpochMs = redeemedAtEpochMs;
    }

    public boolean matches(PresentedCheck presented) {
        return id.equals(presented.id())
                && currency.equals(presented.currency())
                && amount.compareTo(presented.amount()) == 0
                && Objects.equals(issuer, presented.issuer())
                && Objects.equals(payee, presented.payee());
    }

    public UUID id() { return id; }
    public String currency() { return currency; }
    public BigInteger amount() { return amount; }
    public UUID issuer() { return issuer; }
    public String issuerName() { return issuerName; }
    public UUID payee() { return payee; }
    public long issuedAtEpochMs() { return issuedAtEpochMs; }
    public CheckStatus status() { return status; }
    public UUID redeemedBy() { return redeemedBy; }
    public long redeemedAtEpochMs() { return redeemedAtEpochMs; }

    void markRedeemed(UUID player, long when) {
        this.status = CheckStatus.REDEEMED;
        this.redeemedBy = player;
        this.redeemedAtEpochMs = when;
    }
}
