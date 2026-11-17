package dev.jdguo.bank.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.Money;
import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.fx.FxMath;
import dev.jdguo.bank.persistence.BankRuntime;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

/** Read-only market discovery; identity is stable across renames. No synthetic inventory. */
public final class PersonalMarketSnapshot {
    private PersonalMarketSnapshot() {}
    public static String catalog(UUID viewer) { return PersonalMarketCatalog.catalog(BankRuntime.books(), viewer).toString(); }
    public static String quote(UUID buyer, UUID coin, BigInteger amount) {
        BankBooks books = BankRuntime.books();
        var note = books.personalNoteById(coin);
        JsonObject root = new JsonObject();
        TxResult checked = note == null ? TxResult.fail("none", "") : books.quoteListedPersonal(buyer, note.name(), amount);
        root.addProperty("ok", checked.success()); root.addProperty("code", checked.code());
        if (checked.success()) {
            BigDecimal total = books.personalDiamondPrice(note).multiply(books.unitsPer("note", "diamond"), FxMath.MC).multiply(new BigDecimal(amount), FxMath.MC);
            root.addProperty("maximum", Money.stored(total)); root.addProperty("name", note.name());
        }
        return root.toString();
    }
    public static int paymentProtocol() { return 2; }
    public static String quotePayment(UUID buyer, UUID coin, BigInteger amount, String tender) {
        var books = BankRuntime.books();
        if (tender != null && tender.startsWith("personal:")) {
            JsonObject q = books.quotePersonalCross(buyer, coin, amount, tender);
            q.addProperty("nonce", UUID.randomUUID().toString()); q.addProperty("issuedAt", System.currentTimeMillis()); return q.toString();
        }
        var q = books.quotePersonalPayment(buyer, coin, amount, tender);
        JsonObject root = new JsonObject();
        root.addProperty("ok", q.success()); root.addProperty("code", q.code());
        root.addProperty("paymentAmount", "note".equals(tender) ? Money.stored(q.total()) : q.physical().toString());
        root.addProperty("rate", "note".equals(tender) ? "1" : q.physical().signum() > 0
                ? q.total().add(q.change()).divide(new BigDecimal(q.physical())).toPlainString() : "0");
        root.addProperty("fee", "0");
        root.addProperty("details", "note".equals(tender) ? "按真实卖单购买，卖家收取央行券；沿用卖家个人币手续费规则。"
                : "从银行储蓄扣整数实物；央行自有券足额支付成交与找零，不增发、不动用其他玩家余额；沿用卖家个人币手续费规则。");
        root.addProperty("maximum", Money.stored(q.total())); root.addProperty("tender", tender);
        root.addProperty("balance", Money.stored(books.balanceExact(buyer, tender)));
        root.addProperty("tenderName", "diamond".equals(tender) ? "钻石" : "copper".equals(tender) ? "铜锭" : "央行券");
        root.addProperty("physical", q.physical().toString()); root.addProperty("change", Money.stored(q.change()));
        var note = books.personalNoteById(coin);
        if (note != null) root.addProperty("name", note.name());
        return root.toString();
    }
    /** quoteJson is retained by the market server's single-use confirmation, never a client price. */
    public static TxResult buyQuotedPayment(UUID buyer, UUID coin, BigInteger amount, String tender, String quoteJson) {
        try {
            JsonObject q = com.google.gson.JsonParser.parseString(quoteJson).getAsJsonObject();
            if (!q.get("ok").getAsBoolean() || !tender.equals(q.get("tender").getAsString()))
                return TxResult.fail("invalid_quote", "无效报价");
            if (tender.startsWith("personal:")) {
                // Transport metadata is not part of the bank quote retained by the server.
                q.remove("kind"); q.remove("id"); q.remove("quantity"); q.remove("token");
                return BankRuntime.transact(books -> books.buyPersonalCross(buyer, coin, amount, tender, q));
            }
            return buyPayment(buyer, coin, amount, tender, new BigDecimal(q.get("maximum").getAsString()),
                    new BigInteger(q.get("physical").getAsString()), new BigDecimal(q.get("change").getAsString()));
        } catch (RuntimeException invalid) {
            return TxResult.fail("invalid_quote", "无效报价");
        }
    }
    public static TxResult buyPayment(UUID buyer, UUID coin, BigInteger amount, String tender,
            BigDecimal total, BigInteger physical, BigDecimal change) {
        return BankRuntime.transact(books -> books.buyPersonalPayment(buyer, coin, amount, tender, total, physical, change));
    }
    public static TxResult buy(UUID buyer, UUID coin, BigInteger amount, BigDecimal maximum) {
        var note = BankRuntime.books().personalNoteById(coin);
        return note == null ? TxResult.fail("none", "") : MarketSettlement.buyPersonal(buyer, note.name(), amount, maximum);
    }
}
