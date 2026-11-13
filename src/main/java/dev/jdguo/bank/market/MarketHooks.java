package dev.jdguo.bank.market;

/**
 * Later phase. Stocks, then futures and spot, should clear the same way personal notes do:
 * the secondary market only transfers claims, and only issuance or redemption moves reserves.
 * There is no order book, listing, or price tape in this build.
 *
 * <p>A general item shop is also later. When it exists, bankruptcy repayment uses
 * {@link ShopQuote} to value and seize every backpack stack at the live price.
 * Until then the levy only takes registered commodity items such as copper and diamonds.
 */
public interface MarketHooks {
}
