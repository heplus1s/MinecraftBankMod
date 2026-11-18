package dev.jdguo.bank.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.Money;
import java.util.*;

/** Pure snapshot projection: balances stay separate from collateral and never get converted. */
public final class AdminPlayerSnapshot {
    public static final int PAGE_SIZE = 8;
    private AdminPlayerSnapshot() {}

    public static JsonObject page(BankBooks books, Map<UUID, String> names, String search, int requestedPage) {
        String query = search == null ? "" : search.strip();
        if (query.length() > 64) query = query.substring(0, 64);
        final String needle = query.toLowerCase(Locale.ROOT);
        List<UUID> ids = names.keySet().stream()
                .filter(id -> names.get(id).toLowerCase(Locale.ROOT).contains(needle) || id.toString().contains(needle))
                .sorted(Comparator.comparing((UUID id) -> names.get(id).toLowerCase(Locale.ROOT)).thenComparing(UUID::toString))
                .toList();
        int pages = Math.max(1, (ids.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        JsonObject result = new JsonObject();
        result.addProperty("query", query); result.addProperty("page", page);
        result.addProperty("pages", pages); result.addProperty("total", ids.size());
        JsonArray rows = new JsonArray();
        for (int i = page * PAGE_SIZE; i < Math.min(ids.size(), (page + 1) * PAGE_SIZE); i++) {
            UUID id = ids.get(i);
            JsonObject row = new JsonObject();
            row.addProperty("id", id.toString()); row.addProperty("name", names.get(id));
            for (String currency : List.of("note", "diamond", "copper"))
                row.addProperty(currency, Money.stored(books.balanceExact(id, currency)));
            JsonArray holdings = new JsonArray(), issued = new JsonArray();
            for (var note : books.personalNotes()) {
                var balance = books.personalHoldingExact(note.id(), id);
                if (balance.signum() != 0) {
                    JsonObject holding = new JsonObject();
                    holding.addProperty("name", note.name());
                    holding.addProperty("amount", Money.stored(balance));
                    holdings.add(holding);
                }
                if (note.issuer().equals(id)) {
                    JsonObject issue = new JsonObject();
                    issue.addProperty("name", note.name());
                    issue.addProperty("reserveType", currencyName(books, note.reserveCurrency()));
                    issue.addProperty("reserveAmount", Money.stored(books.effectiveBackingExact(note)));
                    issue.addProperty("amount", note.circulation().toString());
                    issue.addProperty("closed", note.bankrupt());
                    issued.add(issue);
                }
            }
            row.add("holdings", holdings); row.add("issued", issued);
            rows.add(row);
        }
        result.add("rows", rows);
        return result;
    }

    private static String currencyName(BankBooks books, String key) {
        var currency = books.currency(key);
        if (currency != null) return currency.name();
        if (key != null && key.startsWith("personal:")) {
            try {
                var note = books.personalNoteById(UUID.fromString(key.substring(9)));
                if (note != null) return note.name();
            } catch (IllegalArgumentException ignored) { }
        }
        return "未知准备金";
    }
}
