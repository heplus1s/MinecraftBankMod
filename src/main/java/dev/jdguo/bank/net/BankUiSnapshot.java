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
        return json(books, player, admin, page, confirm, false);
    }

    static String json(BankBooks books, UUID player, boolean admin, String page, boolean confirm,
                       boolean externalPersonalMarket) {
        JsonObject o = new JsonObject();
        o.addProperty("page", page == null ? "savings" : page);
        o.addProperty("admin", admin);
        o.addProperty("confirm", confirm);
        o.addProperty("externalPersonalMarket", externalPersonalMarket);
        o.addProperty("status", books.statusLabel());
        o.addProperty("copper", Money.stored(books.balanceExact(player, "copper")));
        o.addProperty("diamond", Money.stored(books.balanceExact(player, "diamond")));
        o.addProperty("note", Money.stored(books.balanceExact(player, "note")));
        o.addProperty("savings", Money.stored(books.savingsPoolExact("note")));
        o.addProperty("issuanceDiamond", Money.stored(books.supply("diamond").reserveExact()));
        o.addProperty("vaultDiamond", Money.stored(books.supply("diamond").depositVaultExact()));
        BigDecimal copperRate = books.unitsPer("copper", "diamond");
        if (copperRate != null) {
            o.addProperty("copperPerDiamond", Money.stored(copperRate));
        }
        CurrencyDef centralNote = books.primaryVirtual();
        if (centralNote != null) {
            o.addProperty("centralCurrencyName", centralNote.name());
            putDiamondsPerNote(o, books, centralNote.id());
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
            // diamondsPerNote, on the central note only: diamonds for one 央行券.
            boolean officialNote = centralNote != null && def.id().equals(centralNote.id());
            if ("copper".equals(def.id())) {
                BigDecimal quote = books.unitsPer("copper", "diamond");
                if (quote != null) {
                    row.addProperty("rate", Money.stored(quote));
                    row.addProperty("unitsPerDiamond", Money.stored(quote));
                }
            } else if (officialNote) {
                putStoredQuote(row, "rate", quoted(books, def.id(), books.numeraire()));
                putStoredQuote(row, "unitsPerDiamond", quoted(books, def.id(), "diamond"));
                putDiamondsPerNote(row, books, def.id());
            } else {
                putQuote(row, "rate", quoted(books, def.id(), books.numeraire()));
                putQuote(row, "unitsPerDiamond", quoted(books, def.id(), "diamond"));
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
            book.addProperty("treasury", Money.display(books.treasuryExact(def.id())));
            book.addProperty("custody", Money.display(books.custodyExact(def.id())));
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
            o.addProperty("m0", Money.display(books.centralM0Exact()));
            o.addProperty("m1", Money.display(books.centralM1Exact()));
            o.addProperty("m2", CompactFormat.formatAggregate(books.centralM2()));
            o.addProperty("frozen", books.frozen());
            JsonArray hardBooks = new JsonArray();
            for (String id : new String[] {"copper", "diamond"}) {
                if (books.currency(id) == null) {
                    continue;
                }
                Supply metal = books.supply(id);
                JsonObject row = new JsonObject();
                row.addProperty("id", id);
                row.addProperty("savingsBook", Money.stored(books.savingsBook(id)));
                row.addProperty("depositVault", Money.stored(metal.depositVaultExact()));
                row.addProperty("reserve", Money.stored(metal.reserveExact()));
                hardBooks.add(row);
            }
            o.add("hardBooks", hardBooks);
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
            String noteName = note == null || note.name() == null || note.name().isBlank() ? "央行券" : note.name();
            institutions.add(institutionExact("商店经营账户", noteName, books.shopOperatingExact(noteId)));
            for (PersonalNote personal : books.personalNotes()) {
                BigDecimal held = books.shopOperatingExact("personal:" + personal.id());
                if (held.signum() > 0) {
                    institutions.add(institutionExact("商店经营账户", personal.name(), held));
                }
            }
            institutions.add(institutionExact("土地出让账户", noteName, books.landGrantExact(noteId)));
            for (CurrencyDef def : books.currencies()) {
                if (def.id().equals(noteId)) {
                    continue;
                }
                BigDecimal held = books.landGrantExact(def.id());
                if (held.signum() > 0) {
                    institutions.add(institutionExact("土地出让账户", def.name(), held));
                }
            }
            for (String account : java.util.List.of("shop", "land")) {
                for (var asset : books.systemSettlementAssets(account).entrySet()) {
                    var def = books.currency(asset.getKey());
                    String title = "shop".equals(account) ? "商店结算资产" : "土地结算资产";
                    JsonObject row = institutionExact(title, def == null ? asset.getKey() : def.name(), asset.getValue());
                    BigDecimal rate = "note".equals(asset.getKey()) ? BigDecimal.ONE : books.unitsPer("note", asset.getKey());
                    if (rate != null && rate.signum() > 0) {
                        row.addProperty("detail", row.get("detail").getAsString() + "（估值 "
                                + Money.stored(asset.getValue().multiply(rate, FxMath.MC)) + " 央行券，非现金余额）");
                    }
                    institutions.add(row);
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
            row.addProperty("redemptionPaused", note.redemptionPaused());
            row.addProperty("circulation", whole(note.circulation()));
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
        JsonArray anchorOptions = allowedAnchors();
        for (PersonalNote candidate : books.personalNotes()) {
            if (candidate.bankrupt()) continue;
            JsonObject option = new JsonObject();
            option.addProperty("id", "personal:" + candidate.id());
            option.addProperty("name", candidate.name());
            option.addProperty("label", candidate.name());
            option.addProperty("group", "个人发行货币");
            anchorOptions.add(option);
        }
        if (own != null) {
            for (var element : anchorOptions) {
                JsonObject option = element.getAsJsonObject();
                var eligibility = books.personalReserveEligibility(own.id(), option.get("id").getAsString());
                option.addProperty("allowed", eligibility.allowed());
                option.addProperty("reason", eligibility.reason());
            }
        }
        o.add("anchors", anchorOptions);
        if (own != null) {
            o.addProperty("personal", own.name());
            o.addProperty("regime", own.regime().name());
            o.addProperty("regimeName", BankBooks.regimeZh(own.regime()));
            o.addProperty("anchor", own.anchor());
            o.addProperty("circulation", whole(own.circulation()));
            o.addProperty("reserve", whole(books.centralReserve(player)));
            o.addProperty("required", whole(books.requiredCentralReserve(own)));
            o.addProperty("centralReserve", whole(books.centralReserve(player)));
            o.addProperty("centralRequired", whole(books.requiredCentralReserve(own)));
            putIssuanceReserve(o, books, player, own);
            o.addProperty("holding", whole(books.personalHolding(own.id(), player)));
            o.addProperty("redemptionPaused", own.redemptionPaused());
            o.addProperty("bankrupt", own.bankrupt());
            o.add("ownNote", ownNote(books, player, own));
        }
        return o.toString();
    }

    /**
     * {@code price} is central 央行券 for one personal note: diamond price times the live
     * 央行券-per-diamond quote. {@code diamondPrice} is that same note in diamonds.
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
        BigDecimal centralPrice = books.personalCentralPrice(note);
        if (centralPrice.signum() > 0) row.addProperty("price", CompactFormat.formatPersonalQuote(centralPrice));
        BigDecimal diamonds = books.personalDiamondPrice(note);
        if (diamonds.signum() > 0) row.addProperty("diamondPrice", CompactFormat.formatPersonalQuote(diamonds));
        if (centralPrice.signum() <= 0) {
            BigDecimal active = books.personalActiveExact(note);
            String reason = books.personalReserveSwitching(note.id()) ? "准备金更换中，暂停报价"
                    : note.redemptionPaused() ? "兑付暂停，暂不报价"
                    : active.signum() <= 0 ? "尚无流通货币"
                    : books.effectiveBackingExact(note).signum() <= 0 ? "尚无可用准备金"
                    : "央行券换算牌价暂不可用";
            row.addProperty("quoteStatus", reason);
        }

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

    /** Official 「1 央行券 = xxx 钻石」, including 0. Omitted only when no notes are outstanding. */
    private static void putDiamondsPerNote(JsonObject row, BankBooks books, String noteId) {
        BigDecimal quote = books.unitsPer("diamond", noteId);
        if (quote == null) {
            return;
        }
        row.addProperty("diamondsPerNote", Money.stored(quote));
    }

    private static void putStoredQuote(JsonObject row, String key, BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            return;
        }
        row.addProperty(key, Money.stored(rate));
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
        row.addProperty("id", note.id().toString());
        row.addProperty("canRename", note.issuer().equals(player));
        row.addProperty("autoListing", note.autoListing());
        row.addProperty("autoListingAvailable", true);
        row.addProperty("offered", whole(books.personalOffered(note.id(), note.issuer())));
        row.addProperty("name", note.name());
        row.addProperty("regime", note.regime().name());
        row.addProperty("regimeName", BankBooks.regimeZh(note.regime()));
        row.addProperty("anchor", note.anchor());
        row.addProperty("par", whole(note.par()));
        row.addProperty("locked", whole(note.locked()));
        row.addProperty("personalReserve", whole(books.personalReserve(player, note.reserveCurrency())));
        row.addProperty("centralReserve", whole(books.centralReserve(player)));
        row.addProperty("centralRequired", whole(books.requiredCentralReserve(note)));
        putIssuanceReserve(row, books, player, note);
        row.addProperty("circulation", whole(note.circulation()));
        // Series-wide totals; reserves back liabilities, not additional money supply.
        row.addProperty("m0", whole(books.personalM0(note)));
        row.addProperty("m1", Money.stored(books.personalActiveExact(note)));
        row.addProperty("m2", Money.stored(books.personalActiveExact(note)));
        row.addProperty("m2Basis", "当前无独立准货币账户，M2 与 M1 相同");
        row.addProperty("reserveAmount", Money.stored(books.effectiveBackingExact(note)));
        row.addProperty("reserveCurrency", note.reserveCurrency());
        row.addProperty("clearingEnabled", true);
        row.addProperty("reserveChanging", books.personalReserveSwitching(note.id()));
        row.addProperty("pendingSettlement", books.noteHasPending(note.id()));
        row.addProperty("recovered", Money.stored(books.personalRecovered(note.id())));
        var policy = books.personalPolicy(note.id());
        row.addProperty("policyTarget", policy.target().toPlainString());
        row.addProperty("pegLower", policy.lower().toPlainString());
        row.addProperty("pegUpper", policy.upper().toPlainString());
        BigDecimal active = books.personalActiveExact(note);
        if (active.signum() > 0 && !books.personalReserveSwitching(note.id())) {
            row.addProperty("supportRate", books.effectiveBackingExact(note).divide(active, FxMath.MC).stripTrailingZeros().toPlainString());
        }
        JsonArray history = new JsonArray();
        for (JsonObject event : books.personalSettlements(note.id(), 5)) {
            JsonObject item = new JsonObject();
            item.addProperty("time", java.time.Instant.ofEpochMilli(event.get("timestamp").getAsLong()).toString());
            boolean outbound = event.has("kind") && "personal-outbound".equals(event.get("kind").getAsString());
            boolean reversed = "reversed".equals(event.get("status").getAsString());
            boolean pending = event.has("pendingDelivery") && event.get("pendingDelivery").getAsBoolean();
            StringBuilder details = new StringBuilder(reversed ? "已撤销：" : pending ? "等待交付确认：" : outbound ? "反向结算：" : "兑付结算：");
            for (var entry : event.getAsJsonArray("legs")) {
                JsonObject leg = entry.getAsJsonObject();
                details.append(leg.get("notes").getAsString()).append(" 币 → ")
                        .append(leg.get("reserve").getAsString()).append(" ").append(currencyLabel(books, leg.get("anchor").getAsString())).append("；");
            }
            details.append(outbound ? "系统支出 " : event.has("delivery") ? "个人储蓄入账 " : "系统入账 ").append(event.get("amount").getAsString()).append(" ")
                    .append(currencyLabel(books, event.get("currency").getAsString()));
            item.addProperty("summary", details.toString());
            history.add(item);
        }
        row.add("settlements", history);
        row.addProperty("holding", whole(books.personalHolding(note.id(), player)));
        row.addProperty("redemptionPaused", note.redemptionPaused());
        row.addProperty("face", whole(note.par()));
        BigDecimal live = books.personalDiamondPrice(note);
        if (live != null && live.signum() > 0) {
            row.addProperty("live", CompactFormat.formatPersonalQuote(live));
            row.addProperty("diamondPrice", CompactFormat.formatPersonalQuote(live));
        }
        if (note.openingDiamonds() != null && note.openingDiamonds().signum() > 0) {
            row.addProperty("official", CompactFormat.formatPersonalQuote(note.openingDiamonds()));
        } else if (live != null && live.signum() > 0 && note.regime() != dev.jdguo.bank.market.NoteRegime.FLOAT) {
            row.addProperty("official", CompactFormat.formatPersonalQuote(live));
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

    /** Currencies a personal note may use as its single anchor. Shop and market goods are not included. */
    private static JsonArray allowedAnchors() {
        JsonArray anchors = new JsonArray();
        anchors.add(anchorChoice("diamond", "钻石", "实物货币"));
        anchors.add(anchorChoice("copper", "铜锭", "实物货币"));
        anchors.add(anchorChoice("note", "央行券", "发行货币"));
        return anchors;
    }

    private static JsonObject anchorChoice(String id, String name, String group) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("name", name);
        row.addProperty("group", group);
        return row;
    }

    /**
     * Commodity issuance reserve that 按准备金发行 locks. {@code reserve} / {@code required}
     * stay the 央行券 央行准备金账户 and are not these figures.
     */
    private static void putIssuanceReserve(JsonObject row, BankBooks books, UUID player, PersonalNote note) {
        String currency = note.reserveCurrency();
        CurrencyDef def = books.currency(currency);
        BigDecimal held = books.effectiveBackingExact(note);
        BigDecimal locked = books.personalRequiredBackingExact(note);
        BigDecimal free = books.personalFreeReserveExact(note);
        String name = def == null || def.name() == null ? currency : def.name();
        if (currency != null && currency.startsWith("personal:")) {
            try { var anchor = books.personalNoteById(UUID.fromString(currency.substring(9)));
                if (anchor != null) name = anchor.name();
            } catch (IllegalArgumentException ignored) { }
        }
        row.addProperty("reserveCurrency", currency == null ? "" : currency);
        row.addProperty("reserveCurrencyName", name);
        row.addProperty("issuanceReserve", Money.stored(held));
        row.addProperty("issuanceLocked", Money.stored(locked));
        row.addProperty("issuanceFree", Money.stored(free));
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

    private static String currencyLabel(BankBooks books, String token) {
        var definition = books.currency(token);
        if (definition != null) return definition.name();
        if (token != null && token.startsWith("personal:")) {
            try { var note = books.personalNoteById(UUID.fromString(token.substring(9)));
                if (note != null) return note.name();
            } catch (IllegalArgumentException ignored) { }
        }
        return token;
    }

    private static JsonObject institutionExact(String account, String currency, BigDecimal amount) {
        JsonObject row = new JsonObject();
        row.addProperty("name", account);
        row.addProperty("detail", currency + "  " + Money.stored(amount));
        return row;
    }

    private static JsonObject institution(String account, String currency, BigInteger amount) {
        JsonObject row = new JsonObject();
        row.addProperty("name", account);
        row.addProperty("detail", currency + "  " + CompactFormat.format(amount));
        return row;
    }
}
