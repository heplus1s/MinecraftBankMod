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
    public static TxResult buy(UUID buyer, UUID coin, BigInteger amount, BigDecimal maximum) {
        var note = BankRuntime.books().personalNoteById(coin);
        return note == null ? TxResult.fail("none", "") : MarketSettlement.buyPersonal(buyer, note.name(), amount, maximum);
    }
}
