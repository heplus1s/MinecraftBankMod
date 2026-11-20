package dev.jdguo.bank.api;

import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.persistence.BankRuntime;
import net.fabricmc.loader.api.FabricLoader;

import java.math.BigDecimal;
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
        return postReferenced(reference, "transfer|" + from + "|" + to + "|" + currency + "|" + amount, amount, books -> currency != null && currency.startsWith("personal:")
                ? books.transferPersonalExact(from, to, currency, new BigDecimal(amount))
                : books.transferSavings(from, to, currency, amount));
    }

    /** Read-only availability check. Caller must enforce server administrator authorization. */
    public static TxResult quoteLandBuyback(UUID seller, String currency, BigInteger amount) {
        return read(books -> books.quoteLandBuyback(seller, currency, amount));
    }
    /** Escrow only: seller is credited by completePayment after durable land delivery. */
    public static TxResult prepareLandBuyback(UUID seller, String currency, BigInteger amount, String paymentRef) {
        if (!validReference(paymentRef)) return TxResult.fail("bad_reference", "");
        return BankRuntime.transact(books -> "reversed".equals(books.settlementStatus(paymentRef))
                ? TxResult.fail("reversed", "Buyback was refunded; create a new sale")
                : books.postMarketMovement(paymentRef, "land-buyback|" + seller + "|" + currency + "|" + amount, amount,
                    () -> books.prepareLandBuyback(seller, currency, amount, paymentRef)));
    }
    /** Use for buyback recovery: missing/legacy/reversed receipts fail closed. */
    public static TxResult completeLandBuyback(String paymentRef) {
        return BankRuntime.transact(books -> books.completeLandBuyback(paymentRef));
    }
    /** Restore escrow, never debit spendable seller proceeds. */
    public static TxResult refundLandBuyback(UUID seller, String currency, BigInteger amount, String paymentRef, String refundRef) {
        return postReferenced(refundRef, "refund-land-buyback|" + seller + "|" + currency + "|" + amount + "|" + paymentRef, amount,
                books -> books.refundLandBuyback(seller, currency, amount, paymentRef, refundRef));
    }

    public static String quoteLandPurchase(String denomination, BigInteger total, String tender) {
        return BankRuntime.state == null ? "{\"success\":false}" : BankRuntime.books().quoteLandPurchase(denomination, total, tender).toString();
    }
    /** Strict quoted-land finalization for system resale recovery. */
    public static TxResult completeLandPurchase(String reference) {
        return BankRuntime.transact(books -> books.completeLandPurchase(reference));
    }
    public static TxResult creditLandGrantQuoted(UUID payer, String denomination, BigInteger total, String tender, String fingerprint, String reference) {
        return postReferenced(reference, "land-quoted|" + payer + "|" + denomination + "|" + total + "|" + tender + "|" + fingerprint, total,
                books -> books.payLandPurchase(payer, denomination, total, tender, fingerprint, reference));
    }
    public static TxResult creditLandGrant(UUID payer, String currency, BigInteger amount, String reference) {
        return postReferenced(reference, "grant|" + payer + "|" + currency + "|" + amount, amount, books -> currency != null && currency.startsWith("personal:")
                ? books.settlePersonalToSystem(payer, UUID.fromString(currency.substring(9)), new BigDecimal(amount), "land", reference)
                : books.creditLandGrant(payer, currency, amount));
    }

    public static TxResult refundLandGrant(UUID payer, String currency, BigInteger amount, String reference) {
        String original = reference != null && reference.startsWith("refund:") ? "market-pay:" + reference.substring(7) : "";
        if (BankRuntime.state != null && BankRuntime.books().hasLandPurchase(original))
            return postReferenced(reference, "refund-land-quoted|" + payer + "|" + currency + "|" + amount, amount,
                    books -> books.reverseLandPurchase(payer, currency, amount, original));
        return postReferenced(reference, "refund-grant|" + payer + "|" + currency + "|" + amount, amount, books -> currency != null && currency.startsWith("personal:")
                ? reverseGrant(books, payer, currency, amount, reference)
                : books.refundLandGrant(payer, currency, amount));
    }

    private static TxResult reverseGrant(BankBooks books, UUID payer, String currency, BigInteger amount, String reference) {
        if (reference == null || !reference.startsWith("refund:")) return TxResult.fail("bad_reference", "");
        String original = "market-pay:" + reference.substring("refund:".length());
        var receipt = books.personalSettlementReceipt(original);
        String expected = payer + "|" + currency.substring(9) + "|" + amount + "|land";
        if (receipt == null || !expected.equals(receipt.get("signature").getAsString()))
            return TxResult.fail("reference_conflict", "Refund does not match original payment");
        return books.reversePersonalSettlement(original);
    }

    /** Releases only assets whose land delivery has been durably confirmed by the market. */
    public static TxResult completePayment(String reference) {
        if (!validReference(reference)) return TxResult.fail("bad_reference", "");
        return BankRuntime.transact(books -> books.settlementStatus(reference).equals("none") ? TxResult.ok(BigInteger.ZERO, "Legacy payment") : books.completeSystemPayment(reference));
    }

    /** Same cash-funded payout policy for independent system accounts; never a player-market transfer. */
    public static String quoteSystemPayout(String account, UUID coin, BigDecimal centralBudget) {
        if (BankRuntime.state == null) return "{\"success\":false,\"code\":\"bank_not_ready\"}";
        return BankRuntime.books().quoteSystemPayout(account, coin, centralBudget).toString();
    }

    public static TxResult executeSystemPayout(String account, UUID player, UUID coin,
                                              BigDecimal centralBudget, String fingerprint, String reference) {
        return BankRuntime.transact(books -> books.executeSystemPayout(
                account, player, coin, centralBudget, fingerprint, reference));
    }

    /** Cancels only the referenced, still-escrowed payout; not a new currency purchase. */
    public static TxResult rollbackSystemPayout(String reference) {
        return BankRuntime.transact(books -> books.rollbackShopPayment(reference));
    }

    public static boolean externalPersonalMarket() {
        return FabricLoader.getInstance().isModLoaded("marketmod");
    }

    public static TxResult personalOffers(UUID viewer, String name) {
        return read(books -> name == null ? books.browsePersonalOffers(viewer) : books.viewPersonalOffers(viewer, name));
    }

    public static TxResult quotePersonal(UUID buyer, String name, BigInteger amount) {
        return read(books -> books.quoteListedPersonal(buyer, name, amount));
    }

    public static TxResult listPersonal(UUID holder, String name, BigInteger amount) {
        return post("personal-listing", books -> books.listPersonal(holder, name, amount));
    }

    public static TxResult buyPersonal(UUID buyer, String name, BigInteger amount, BigDecimal maximum) {
        return post("personal-purchase", books -> books.buyListedPersonal(buyer, name, amount, maximum));
    }

    private static TxResult read(Movement movement) {
        if (BankRuntime.state == null) {
            return TxResult.fail("bank_not_ready", "Ledger is not loaded");
        }
        return movement.apply(BankRuntime.books());
    }

    public static TxResult marketReceipt(String reference) {
        if (!validReference(reference)) {
            return TxResult.fail("bad_reference", "");
        }
        return read(books -> books.marketReceipt(reference) ? TxResult.ok(BigInteger.ZERO, "")
                : TxResult.fail("unrecorded", ""));
    }

    private static boolean validReference(String reference) {
        return reference != null && !reference.isBlank() && reference.length() <= 128;
    }

    private static TxResult postReferenced(String reference, String fingerprint, BigInteger amount, Movement movement) {
        if (!validReference(reference)) {
            return TxResult.fail("bad_reference", "");
        }
        return BankRuntime.transact(books ->
                books.postMarketMovement(reference, fingerprint, amount, () -> movement.apply(books)));
    }

    private static TxResult post(String reference, Movement movement) {
        if (!validReference(reference)) {
            return TxResult.fail("bad_reference", "Missing reference");
        }
        // Listing edits and fills share the same durable ledger boundary as shop
        // on-demand subscriptions. Never expose an in-memory fill before saving it.
        return BankRuntime.transact(movement::apply);
    }

    @FunctionalInterface
    private interface Movement {
        TxResult apply(BankBooks books);
    }
}
