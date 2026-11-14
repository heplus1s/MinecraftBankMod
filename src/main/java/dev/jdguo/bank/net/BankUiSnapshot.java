package dev.jdguo.bank.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.jdguo.bank.currency.CurrencyDef;
import dev.jdguo.bank.currency.CurrencyKind;
import dev.jdguo.bank.fx.FxMath;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.CompactFormat;
import dev.jdguo.bank.ledger.Money;
import dev.jdguo.bank.ledger.Supply;
import dev.jdguo.bank.market.PersonalNote;
import dev.jdguo.bank.market.RegimePreview;

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
        o.addProperty("copper", Money.stored(books.balanceExact(player, "copper")));
        o.addProperty("diamond", Money.stored(books.balanceExact(player, "diamond")));
        o.addProperty("note", Money.stored(books.balanceExact(player, "note")));
        o.addProperty("savings", Money.stored(books.savingsPoolExact("note")));
        o.addProperty("issuanceDiamond", Money.stored(books.supply("diamond").reserveExact()));
        o.addProperty("vaultDiamond", Money.stored(books.supply("diamond").depositVaultExact()));
        putQuote(o, "copperPerDiamond", quoted(books, "copper", "diamond"));
        CurrencyDef centralNote = books.primaryVirtual();
        if (centralNote != null) {
            putQuote(o, "diamondsPerNote", quoted(books, "diamond", centralNote.id()));
        }
        JsonArray currencies = new JsonArray();
        JsonArray accounts = new JsonArray();
        JsonArray central = new JsonArray();
        for (CurrencyDef def : books.currencies()) {
            String shown = Money.display(books.balanceExact(player, def.id()));
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
            Supply supply = books.supply(def.id());
            JsonObject book = new JsonObject();
            book.addProperty("id", def.id());
            book.addProperty("name", def.name());
            book.addProperty("savings", Money.display(books.savingsPoolExact(def.id())));
            book.addProperty("reserve", Money.display(supply.reserveExact()));
            book.addProperty("liability", Money.display(supply.liabilityExact()));
            book.addProperty("locked", Money.display(supply.lockedExact()));
            book.addProperty("backedRoom", CompactFormat.format(books.backedRoom(def)));
            if (def.kind() == CurrencyKind.COMMODITY) {
                book.addProperty("vault", Money.display(supply.depositVaultExact()));
            }
            central.add(book);
        }
        o.add("currencies", currencies);
        o.add("accounts", accounts);
        o.add("central", central);
        if (admin) {
            o.addProperty("frozen", books.frozen());
            o.addProperty("feePercent", percent(books.feeFraction()));
            if (centralNote != null) {
                BigDecimal tight = books.tightness(centralNote.id());
                if (tight != null) {
                    o.addProperty("tightness", CompactFormat.formatRatio(tight));
                }
            }
            putMoney(o, "copperReserve", books.currency("copper") == null ? null : books.supply("copper").reserveExact());
            putMoney(o, "diamondReserve", books.currency("diamond") == null ? null : books.supply("diamond").reserveExact());
            putMoney(o, "noteReserve", centralNote == null ? null : books.supply(centralNote.id()).reserveExact());
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
            o.addProperty("regimeName", BankBooks.regimeZh(own.regime()));
            o.addProperty("anchor", own.anchor());
            o.addProperty("circulation", whole(own.circulation()));
            o.addProperty("reserve", whole(books.centralReserve(player)));
            o.addProperty("required", whole(books.requiredCentralReserve(own)));
            o.addProperty("bankrupt", own.bankrupt());
            o.add("ownNote", ownNote(books, player, own));
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

    private static JsonObject ownNote(BankBooks books, UUID player, PersonalNote note) {
        JsonObject row = new JsonObject();
        row.addProperty("name", note.name());
        row.addProperty("regime", note.regime().name());
        row.addProperty("regimeName", BankBooks.regimeZh(note.regime()));
        row.addProperty("anchor", note.anchor());
        row.addProperty("par", whole(note.par()));
        row.addProperty("locked", whole(note.locked()));
        row.addProperty("personalReserve", whole(books.personalReserve(player, note.anchor())));
        row.addProperty("centralReserve", whole(books.centralReserve(player)));
        row.addProperty("circulation", whole(note.circulation()));
        row.addProperty("face", whole(note.par()));
        BigDecimal live = books.personalDiamondPrice(note);
        if (live != null && live.signum() > 0) {
            row.addProperty("live", CompactFormat.formatRate(live));
            row.addProperty("diamondPrice", CompactFormat.formatRate(live));
        }
        if (note.openingDiamonds() != null && note.openingDiamonds().signum() > 0) {
            row.addProperty("official", CompactFormat.formatRate(note.openingDiamonds()));
        } else if (live != null && live.signum() > 0 && note.regime() != dev.jdguo.bank.market.NoteRegime.FLOAT) {
            row.addProperty("official", CompactFormat.formatRate(live));
        }
        JsonArray switches = new JsonArray();
        for (RegimePreview option : books.regimePreviews(note)) {
            JsonObject choice = new JsonObject();
            choice.addProperty("regime", option.regime().name());
            choice.addProperty("anchor", option.anchor());
            choice.addProperty("name", BankBooks.regimeZh(option.regime()));
            choice.addProperty("allowed", option.allowed());
            if (!option.allowed() && !option.reason().isBlank()) {
                choice.addProperty("reason", option.reason());
            }
            switches.add(choice);
        }
        row.add("switches", switches);
        return row;
    }

    /** Whole units. Zero is {@code 0}, never a six-decimal placeholder. */
    private static String whole(BigInteger amount) {
        if (amount == null || amount.signum() == 0) {
            return "0";
        }
        return CompactFormat.format(amount);
    }

    private static void putMoney(JsonObject row, String key, BigDecimal amount) {
        if (amount == null) {
            return;
        }
        row.addProperty(key, Money.display(amount));
    }

    private static void putWhole(JsonObject row, String key, BigInteger amount) {
        if (amount == null) {
            return;
        }
        row.addProperty(key, whole(amount));
    }

    /** Same percent string the chest prints, for example {@code 2%}. */
    private static String percent(BigDecimal fraction) {
        if (fraction == null) {
            return null;
        }
        return fraction.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
    }

    private static JsonObject institution(String account, String currency, BigInteger amount) {
        JsonObject row = new JsonObject();
        row.addProperty("name", account);
        row.addProperty("detail", currency + "  " + CompactFormat.format(amount));
        return row;
    }
}
