package dev.jdguo.bank.api;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.Money;
import dev.jdguo.bank.fx.FxMath;
import java.math.BigDecimal;
import java.util.UUID;
/** Pure projection: creation creates a directory entry, not units or a synthetic order. */
public final class PersonalMarketCatalog {
    private PersonalMarketCatalog() {}
    public static JsonObject catalog(BankBooks books, UUID viewer) {
        JsonObject root = new JsonObject();
        root.addProperty("balance", Money.stored(books.balanceExact(viewer, "note")));
        root.addProperty("centralName", books.currency("note").name());
        JsonArray rows = new JsonArray();
        for (var note : books.personalNotes()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", note.id().toString()); row.addProperty("name", note.name());
            row.addProperty("issuer", note.issuer().toString());
            row.addProperty("held", Money.stored(books.personalHoldingExact(note.id(), viewer)));
            row.addProperty("offered", books.personalOffered(note.id(), viewer).toString());
            row.addProperty("available", books.availablePersonalOffer(note.id(), viewer).toString());
            row.addProperty("issuerOffer", books.personalOffered(note.id(), note.issuer()).toString());
            row.addProperty("autoListing", note.autoListing());
            BigDecimal rate = books.unitsPer("note", "diamond");
            boolean trading = books.personalTradingAllowed(note) && rate != null && rate.signum() > 0;
            BigDecimal price = trading ? books.personalDiamondPrice(note).multiply(rate, FxMath.MC) : BigDecimal.ZERO;
            row.addProperty("tradable", trading && price.signum() > 0);
            row.addProperty("price", price.signum() > 0 ? price.stripTrailingZeros().toPlainString() : "");
            rows.add(row);
        }
        root.add("notes", rows); return root;
    }
}
