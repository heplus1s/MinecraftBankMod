package dev.jdguo.bank.market;

import java.math.BigDecimal;

/**
 * Future item shop. Not implemented in this build, and there is no price list here.
 *
 * <p>Once a shop exists, bankruptcy repayment will value every backpack stack at that
 * shop's live quote and seize it against the central-bank debt, not only copper and
 * diamonds. {@link IssuerLevy} keeps seizing registered commodity items on its own.
 * A shop installs itself with {@link #install}. A missing quote means the stack stays.
 */
public interface ShopQuote {
    /**
     * Diamonds for one of {@code itemId} at the live shop price.
     * Return null when the shop does not price that item.
     */
    BigDecimal diamondsEach(String itemId);

    static ShopQuote installed() {
        return Holder.quote;
    }

    static void install(ShopQuote quote) {
        Holder.quote = quote;
    }

    final class Holder {
        private static ShopQuote quote;

        private Holder() {}
    }
}
