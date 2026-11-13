package dev.jdguo.bank.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.jdguo.bank.currency.CurrencyDef;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.CompactFormat;
import dev.jdguo.bank.market.PersonalNote;

import java.util.UUID;

/** Dashboard the optional client UI renders. The server builds it; the client never calculates money. */
public final class BankUiSnapshot {
    private BankUiSnapshot() {}

    public static String json(BankBooks books, UUID player, boolean admin, String page, boolean confirm) {
        JsonObject o = new JsonObject();
        o.addProperty("page", page == null ? "savings" : page);
        o.addProperty("admin", admin);
        o.addProperty("confirm", confirm);
        o.addProperty("m0", CompactFormat.formatAggregate(books.centralM0()));
        o.addProperty("m1", CompactFormat.formatAggregate(books.centralM1()));
        o.addProperty("m2", CompactFormat.formatAggregate(books.centralM2()));
        o.addProperty("status", books.statusLabel());
        o.addProperty("copper", books.balance(player, "copper").toString());
        o.addProperty("diamond", books.balance(player, "diamond").toString());
        o.addProperty("note", books.balance(player, "note").toString());
        o.addProperty("savings", books.savingsPool("note").toString());
        o.addProperty("issuanceDiamond", books.supply("diamond").reserve().toString());
        o.addProperty("vaultDiamond", books.supply("diamond").depositVault().toString());
        JsonArray currencies = new JsonArray();
        for (CurrencyDef def : books.currencies()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", def.id());
            row.addProperty("name", def.name());
            row.addProperty("balance", books.balance(player, def.id()).toString());
            currencies.add(row);
        }
        o.add("currencies", currencies);
        JsonArray notes = new JsonArray();
        for (PersonalNote note : books.personalNotes()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", note.id().toString());
            row.addProperty("name", note.name());
            row.addProperty("balance", books.personalHolding(note.id(), player).toString());
            notes.add(row);
        }
        o.add("notes", notes);
        PersonalNote own = books.personalNote(player);
        if (own != null) {
            o.addProperty("personal", own.name());
            o.addProperty("regime", own.regime().name());
            o.addProperty("circulation", own.circulation().toString());
            o.addProperty("reserve", books.centralReserve(player).toString());
            o.addProperty("required", books.requiredCentralReserve(own).toString());
            o.addProperty("bankrupt", own.bankrupt());
        }
        return o.toString();
    }
}
