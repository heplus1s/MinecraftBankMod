package dev.jdguo.bank.instruments;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Redemption registry. The first successful redeem wins; a second copy of the same id pays nothing.
 * Matching happens before the status check so a rewritten amount cannot sneak through as a fresh bill.
 */
public final class CheckBook {
    public enum RedeemCode { OK, UNKNOWN, TAMPERED, ALREADY, WRONG_PAYEE }

    private final Map<UUID, CheckRecord> records = new LinkedHashMap<>();

    public CheckRecord create(String currency, BigInteger amount, UUID issuer, String issuerName, UUID payee, long now) {
        CheckRecord record = new CheckRecord(UUID.randomUUID(), currency, amount, issuer, issuerName, payee, now,
                CheckStatus.OUTSTANDING, null, 0L);
        records.put(record.id(), record);
        return record;
    }

    public void restore(CheckRecord record) {
        records.put(record.id(), record);
    }

    public CheckRecord get(UUID id) {
        return records.get(id);
    }

    public Collection<CheckRecord> all() {
        return Collections.unmodifiableCollection(records.values());
    }

    public RedeemCode redeem(UUID player, PresentedCheck presented, long now) {
        CheckRecord record = records.get(presented.id());
        if (record == null) {
            return RedeemCode.UNKNOWN;
        }
        if (!record.matches(presented)) {
            return RedeemCode.TAMPERED;
        }
        if (record.status() == CheckStatus.REDEEMED) {
            return RedeemCode.ALREADY;
        }
        if (record.payee() != null && !record.payee().equals(player)) {
            return RedeemCode.WRONG_PAYEE;
        }
        record.markRedeemed(player, now);
        return RedeemCode.OK;
    }
}
