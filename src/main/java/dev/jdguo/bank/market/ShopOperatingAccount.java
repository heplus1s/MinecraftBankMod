package dev.jdguo.bank.market;

import dev.jdguo.bank.currency.CurrencyDef;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.persistence.BankRuntime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

/**
 * 商店经营账户. A sub-ledger of 储蓄总池, beside personal demand deposits.
 *
 * <p>The shop mod looks this class up by reflection and does not need the bank to depend on it.
 * {@link #transfer} moves the central note between a player's demand deposit and this account.
 * Issuance reserves, personal issuance vaults, paper, and the land-grant account stay put.
 * The balance earns the same demand-deposit interest as a personal note balance; the shop does
 * not pay that interest itself.
 */
public final class ShopOperatingAccount {
    private static final ShopOperatingAccount LIVE = new ShopOperatingAccount();

    private ShopOperatingAccount() {}

    /** Null until the world ledger is loaded. */
    public static ShopOperatingAccount get() {
        return BankRuntime.state == null ? null : LIVE;
    }

    /** Central virtual currency, normally {@code note}. */
    public String currencyId() {
        BankBooks books = BankRuntime.books();
        CurrencyDef note = books.primaryVirtual();
        return note == null || note.id() == null ? "note" : note.id();
    }

    /** Operating-account balance, in whole units of {@link #currencyId()}. */
    public BigInteger balance() {
        return BankRuntime.books().shopOperating(currencyId());
    }

    /** Player demand-deposit balance in that same currency. */
    public BigInteger playerBalance(UUID player) {
        if (player == null) {
            return BigInteger.ZERO;
        }
        return BankRuntime.books().balance(player, currencyId());
    }

    /** Four-decimal balance expected by the shop's live bridge. */
    public BigDecimal balance(String currency) {
        if (currency != null && currency.startsWith("personal:")) {
            try {
                BankBooks books = BankRuntime.books();
                return books.systemPersonalPayoutCapacity("shop", UUID.fromString(currency.substring(9)));
            } catch (IllegalArgumentException invalid) { return BigDecimal.ZERO; }
        }
        return BankRuntime.books().systemAvailableBalance("shop", currency);
    }

    /** Four-decimal player balance; never truncate fractional spending power. */
    public BigDecimal playerBalance(UUID player, String currency) {
        if (player == null || currency == null) return BigDecimal.ZERO;
        BankBooks books = BankRuntime.books();
        if (currency.startsWith("personal:")) {
            try {
                return books.personalHoldingExact(
                        UUID.fromString(currency.substring("personal:".length())), player);
            } catch (IllegalArgumentException error) {
                return BigDecimal.ZERO;
            }
        }
        String noteId = currencyId();
        if (!"note".equals(currency) && !noteId.equals(currency)) return BigDecimal.ZERO;
        return books.balanceExact(player, noteId);
    }

    /** Receipt-backed durable payment used by the integrated shop. */
    public TxResult transferReferenced(UUID player, String currency, BigDecimal amount, boolean playerPaysShop, String reference) {
        return BankRuntime.transact(books -> books.transferShopReferenced(player, currency, amount, playerPaysShop, reference));
    }

    /** Structured preview; no holdings, reserves, or recovered coins are changed. */
    public String quoteIssuerPayout(UUID coin, BigDecimal centralBudget) {
        if (BankRuntime.state == null) return "{\"success\":false,\"code\":\"bank_not_ready\"}";
        return BankRuntime.books().quoteSystemPayout(coin, centralBudget).toString();
    }

    /** Atomically buys actually listed fractional issuer coins; new payouts never substitute central-note change. */
    public TxResult payIssuerOffer(UUID player, UUID coin, BigDecimal centralBudget,
                                   String fingerprint, String reference) {
        return BankRuntime.transact(books -> books.executeSystemPayout(
                player, coin, centralBudget, fingerprint, reference));
    }

    public TxResult rollback(String reference) {
        return BankRuntime.transact(books -> books.rollbackShopPayment(reference));
    }

    public String receiptStatus(String reference) {
        return BankRuntime.state == null ? "unavailable" : BankRuntime.books().settlementStatus(reference);
    }

    public TxResult completePayment(String reference) {
        return BankRuntime.transact(books -> books.completeSystemPayment(reference));
    }

    public boolean durablePayments() { return true; }

    /**
     * Moves whole units inside the savings pool.
     *
     * @param playerPaysShop {@code true} when the player buys from the server
     * @return success, or {@code insufficient_funds}, {@code insufficient_shop}, or {@code bad_amount}
     */
    public TxResult transfer(UUID player, BigInteger amount, boolean playerPaysShop) {
        return transfer(player, currencyId(), amount == null ? null : new BigDecimal(amount), playerPaysShop);
    }

    /**
     * Moves a four-decimal amount. {@code currency} is {@code note} or {@code personal:<uuid>}.
     * A short balance refuses the whole transfer.
     *
     * @param playerPaysShop {@code true} when the player buys from the server
     * @return success, or {@code insufficient_funds}, {@code insufficient_shop}, or {@code bad_amount}
     */
    public TxResult transfer(UUID player, String currency, BigDecimal amount, boolean playerPaysShop) {
        if (currency != null && currency.startsWith("personal:")) {
            return TxResult.fail("durable_required", "个人币结算需要配套新版商店的引用交易接口");
        }
        if (BankRuntime.state == null) {
            return TxResult.fail("bank_not_ready", "Ledger is not loaded");
        }
        TxResult result = BankRuntime.books().transferShop(player, currency, amount, playerPaysShop);
        if (result.success()) {
            BankRuntime.save();
        }
        return result;
    }
}
