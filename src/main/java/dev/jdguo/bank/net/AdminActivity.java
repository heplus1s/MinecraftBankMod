package dev.jdguo.bank.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.jdguo.bank.ledger.BankBooks;
import net.minecraft.server.network.ServerPlayerEntity;

/** Paginates the unified administrative list before encoding it for the client. */
final class AdminActivity {
    private static final int PER_PAGE = 24;
    private AdminActivity() {}

    static JsonObject page(ServerPlayerEntity viewer, BankBooks books, int requested) {
        var result = new JsonObject();
        var entries = books.adminActivity();
        int pages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
        int index = Math.min(Math.max(requested, 0), pages - 1);
        result.addProperty("page", index); result.addProperty("pages", pages);
        var rows = new JsonArray();
        var server = viewer.getServer();
        var names = new java.util.HashMap<java.util.UUID, String>();
        for (var entry : entries.subList(index * PER_PAGE, Math.min(entries.size(), (index + 1) * PER_PAGE))) {
            String name = names.computeIfAbsent(entry.player(), id -> {
                var online = server.getPlayerManager().getPlayer(id);
                if (online != null) return online.getGameProfile().getName();
                if (server.getUserCache() != null)
                    return server.getUserCache().getByUuid(id).map(profile -> profile.getName()).orElse("");
                return "";
            });
            var row = new JsonObject();
            row.addProperty("time", entry.time());
            row.addProperty("name", !entry.name().isBlank() ? entry.name() : !name.isBlank() ? name : entry.player().toString());
            row.addProperty("action", entry.action());
            var def = books.currency(entry.currency());
            var personal = entry.currency().startsWith("personal:")
                    ? books.personalNoteById(java.util.UUID.fromString(entry.currency().substring(9))) : null;
            row.addProperty("currency", def != null ? def.name() : personal != null ? personal.name() : entry.currency());
            row.addProperty("amount", entry.amount().toString());
            if (entry.bill() != null) row.addProperty("bill", entry.bill().toString());
            rows.add(row);
        }
        result.add("rows", rows);
        return result;
    }
}
