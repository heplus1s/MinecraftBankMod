package dev.jdguo.bank.api;

import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.persistence.BankRuntime;

import java.math.BigInteger;
import java.util.UUID;

/**
 * Savings movements the player market posts without writing this ledger itself.
 *
 * <p>{@link #transfer} pays a seller, or refunds that payment. The price is not income of the
 * central bank. {@link #creditLandGrant} is only for server-sold land, and later for disposal
 * of recovered land. {@link #refundLandGrant} reverses that credit. Both the shop operating
 * account and this land-grant account are sub-ledgers of 储蓄总池. Neither call touches
 * issuance reserves, personal issuance vaults, or paper. A failed call leaves every balance
 * unchanged. A successful call saves the ledger before it returns.
 */
public final class MarketSettlement {
    private MarketSettlement() {}

    public static TxResult transfer(UUID from, UUID to, String currency, BigInteger amount, String reference) {
        return post(reference, books -> books.transferSavings(from, to, currency, amount));
    }

    public static TxResult creditLandGrant(UUID payer, String currency, BigInteger amount, String reference) {
        return post(reference, books -> books.creditLandGrant(payer, currency, amount));
    }

    public static TxResult refundLandGrant(UUID payer, String currency, BigInteger amount, String reference) {
        return post(reference, books -> books.refundLandGrant(payer, currency, amount));
    }

    private static TxResult post(String reference, Movement movement) {
        if (reference == null || reference.isBlank() || reference.length() > 128) {
            return TxResult.fail("bad_reference", "Missing reference");
        }
        if (BankRuntime.state == null) {
            return TxResult.fail("bank_not_ready", "Ledger is not loaded");
        }
        TxResult result = movement.apply(BankRuntime.books());
        if (result.success()) {
            BankRuntime.save();
        }
        return result;
    }

    @FunctionalInterface
    private interface Movement {
        TxResult apply(BankBooks books);
    }
}
