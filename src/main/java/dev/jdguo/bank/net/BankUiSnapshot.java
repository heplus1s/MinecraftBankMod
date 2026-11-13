package dev.jdguo.bank.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.jdguo.bank.currency.CurrencyDef;
import dev.jdguo.bank.currency.CurrencyKind;
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
        JsonArray accounts = new JsonArray();
        JsonArray central = new JsonArray();
        for (CurrencyDef def : books.currencies()) {
            String shown = CompactFormat.format(books.balance(player, def.id()));
            JsonObject row = new JsonObject();
            row.addProperty("id", def.id());
            row.addProperty("name", def.name());
            row.addProperty("balance", shown);
            currencies.add(row);
            JsonObject account = new JsonObject();
            account.addProperty("name", def.name());
            account.addProperty("amount", shown);
            accounts.add(account);
            JsonObject book = new JsonObject();
            book.addProperty("name", def.name());
            book.addProperty("savings", CompactFormat.format(books.savingsPool(def.id())));
            if (def.kind() == CurrencyKind.COMMODITY) {
                book.addProperty("reserve", CompactFormat.format(books.supply(def.id()).reserve()));
                book.addProperty("vault", CompactFormat.format(books.supply(def.id()).depositVault()));
            }
            central.add(book);
        }
        o.add("currencies", currencies);
        o.add("accounts", accounts);
        o.add("central", central);
        JsonArray notes = new JsonArray();
        for (PersonalNote note : books.personalNotes()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", note.id().toString());
            row.addProperty("name", note.name());
            String shown = CompactFormat.format(books.personalHolding(note.id(), player));
            row.addProperty("balance", shown);
            notes.add(row);
            if (books.personalHolding(note.id(), player).signum() > 0) {
                JsonObject account = new JsonObject();
                account.addProperty("name", note.name());
                account.addProperty("amount", shown);
                accounts.add(account);
            }
        }
        o.add("notes", notes);
        PersonalNote own = books.personalNote(player);
        if (own != null) {
            o.addProperty("personal", own.name());
            o.addProperty("regime", own.regime().name());
            o.addProperty("circulation", CompactFormat.formatAggregate(own.circulation()));
            o.addProperty("reserve", CompactFormat.format(books.centralReserve(player)));
            o.addProperty("required", CompactFormat.format(books.requiredCentralReserve(own)));
            o.addProperty("bankrupt", own.bankrupt());
        }
        return o.toString();
    }
}
