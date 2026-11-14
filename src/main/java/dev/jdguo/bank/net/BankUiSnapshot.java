package dev.jdguo.bank.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.jdguo.bank.currency.CurrencyDef;
import dev.jdguo.bank.currency.CurrencyKind;
import dev.jdguo.bank.fx.FxMath;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.CompactFormat;
import dev.jdguo.bank.market.PersonalNote;

import java.math.BigDecimal;
import java.math.BigInteger;
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
        putQuote(o, "copperPerDiamond", quoted(books, "copper", "diamond"));
        CurrencyDef centralNote = books.primaryVirtual();
        if (centralNote != null) {
            putQuote(o, "diamondsPerNote", quoted(books, "diamond", centralNote.id()));
        }
        JsonArray currencies = new JsonArray();
        JsonArray accounts = new JsonArray();
        JsonArray central = new JsonArray();
        for (CurrencyDef def : books.currencies()) {
            String shown = CompactFormat.format(books.balance(player, def.id()));
            JsonObject row = new JsonObject();
            row.addProperty("id", def.id());
            row.addProperty("name", def.name());
            row.addProperty("balance", shown);
            // rate / unitsPerDiamond: units of this currency for one numeraire or one diamond.
            // diamondsPerNote, on the central note only: diamonds for one 银行券.
            putQuote(row, "rate", quoted(books, def.id(), books.numeraire()));
            putQuote(row, "unitsPerDiamond", quoted(books, def.id(), "diamond"));
            if (centralNote != null && def.id().equals(centralNote.id())) {
                putQuote(row, "diamondsPerNote", quoted(books, "diamond", def.id()));
            }
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
        if (admin) {
            JsonArray institutions = new JsonArray();
            CurrencyDef note = books.primaryVirtual();
            String noteId = note == null ? "note" : note.id();
            String noteName = note == null || note.name() == null || note.name().isBlank() ? "银行券" : note.name();
            institutions.add(institution("商店经营账户", noteName, books.shopOperating(noteId)));
            institutions.add(institution("土地出让账户", noteName, books.landGrant(noteId)));
            for (CurrencyDef def : books.currencies()) {
                if (def.id().equals(noteId)) {
                    continue;
                }
                BigInteger held = books.landGrant(def.id());
                if (held.signum() > 0) {
                    institutions.add(institution("土地出让账户", def.name(), held));
                }
            }
            o.add("institutions", institutions);
        }
        JsonArray notes = new JsonArray();
        for (PersonalNote note : books.personalNotes()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", note.id().toString());
            row.addProperty("name", note.name());
            String shown = CompactFormat.format(books.personalHolding(note.id(), player));
            row.addProperty("balance", shown);
            putPersonalNotePrice(row, books, note);
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

    /**
     * {@code price} is central 银行券 for one personal note: diamond price times the live
     * 银行券-per-diamond quote. {@code diamondPrice} is that same note in diamonds.
     * Both are omitted when either quote is missing, so the client can say 暂无牌价.
     */
    private static void putPersonalNotePrice(JsonObject row, BankBooks books, PersonalNote note) {
        if (note.bankrupt()) {
            return;
        }
        CurrencyDef central = books.primaryVirtual();
        if (central == null) {
            return;
        }
        BigDecimal diamonds = books.personalDiamondPrice(note);
        BigDecimal notesPerDiamond = quoted(books, central.id(), "diamond");
        if (diamonds == null || diamonds.signum() <= 0 || notesPerDiamond == null) {
            return;
        }
        putQuote(row, "price", diamonds.multiply(notesPerDiamond, FxMath.MC));
        putQuote(row, "diamondPrice", diamonds);
    }

    /** Units of {@code pay} for one unit of {@code receive}, or null when the books cannot quote. */
    private static BigDecimal quoted(BankBooks books, String pay, String receive) {
        if (pay == null || receive == null || books.currency(pay) == null || books.currency(receive) == null) {
            return null;
        }
        BigDecimal rate = books.unitsPer(pay, receive);
        if (rate == null || rate.signum() <= 0) {
            return null;
        }
        return rate;
    }

    private static void putQuote(JsonObject row, String key, BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            return;
        }
        String shown = CompactFormat.formatRate(rate);
        if (shown == null || shown.isBlank() || "无法报价".equals(shown)) {
            return;
        }
        row.addProperty(key, shown);
    }

    private static JsonObject institution(String account, String currency, BigInteger amount) {
        JsonObject row = new JsonObject();
        row.addProperty("name", account);
        row.addProperty("detail", currency + "  " + CompactFormat.format(amount));
        return row;
    }
}
