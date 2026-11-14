package dev.jdguo.bank.market;

import dev.jdguo.bank.currency.CurrencyDef;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.persistence.BankRuntime;

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

    /**
     * Moves whole units inside the savings pool.
     *
     * @param playerPaysShop {@code true} when the player buys from the server
     * @return success, or {@code insufficient_funds}, {@code insufficient_shop}, or {@code bad_amount}
     */
    public TxResult transfer(UUID player, BigInteger amount, boolean playerPaysShop) {
        if (BankRuntime.state == null) {
            return TxResult.fail("bank_not_ready", "Ledger is not loaded");
        }
        TxResult result = BankRuntime.books().transferShopOperating(player, amount, playerPaysShop);
        if (result.success()) {
            BankRuntime.save();
        }
        return result;
    }
}
