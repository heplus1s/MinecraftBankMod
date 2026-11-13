package dev.jdguo.bank.ledger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jdguo.bank.credit.CreditDesk;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.currency.CurrencyDef;
import dev.jdguo.bank.currency.CurrencyKind;
import dev.jdguo.bank.fx.FxMath;
import dev.jdguo.bank.instruments.CheckBook;
import dev.jdguo.bank.instruments.CheckRecord;
import dev.jdguo.bank.instruments.CheckStatus;
import dev.jdguo.bank.instruments.NoteSeries;
import dev.jdguo.bank.instruments.PaperTake;
import dev.jdguo.bank.instruments.PresentedCheck;
import dev.jdguo.bank.instruments.PresentedNote;
import dev.jdguo.bank.market.NoteTrade;
import dev.jdguo.bank.market.PersonalMarket;
import dev.jdguo.bank.market.PersonalNote;
import dev.jdguo.bank.market.Redemption;
import dev.jdguo.bank.market.SecondaryClearing;
import dev.jdguo.bank.market.SwapTrade;
import dev.jdguo.bank.outlet.OutletPoint;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * World ledger. Not thread-safe; the Minecraft server thread is the only caller.
 * Money is {@link BigInteger} base units (one copper ingot, one diamond, one note).
 * Rates are {@link BigDecimal}. Nothing here touches a scoreboard.
 */
public final class BankBooks {
    public static final BigInteger MAX_PHYSICAL = BigInteger.valueOf(4096);
    public static final int SNAPSHOT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    private final Map<String, CurrencyDef> currencies;
    private final Map<String, Supply> supplies = new LinkedHashMap<>();
    private final Map<UUID, Map<String, BigInteger>> accounts = new LinkedHashMap<>();
    /** Per-player reserves for a later personal mint. Not savings, and not the central vault. */
    private final Map<UUID, Map<String, BigInteger>> personalReserves = new LinkedHashMap<>();
    private final Map<UUID, PersonalNote> personalNotes = new LinkedHashMap<>();
    private final Map<UUID, Map<UUID, BigInteger>> personalHoldings = new LinkedHashMap<>();
    private final CheckBook checks = new CheckBook();
    /** Sheets of stackable paper still out. Keyed by series, not by individual item. */
    private final Map<String, BigInteger> notePaper = new LinkedHashMap<>();
    private final List<OutletPoint> outlets = new ArrayList<>();
    private final Map<UUID, DisplayPref> prefs = new LinkedHashMap<>();
    private final Map<String, BigDecimal> alphas = new LinkedHashMap<>();
    private final java.util.Set<UUID> imported = new java.util.LinkedHashSet<>();

    private String numeraire;
    private BigDecimal feeFraction;
    private BigDecimal clampMin;
    private BigDecimal clampMax;
    private boolean frozen;
    private boolean globalSidebar = true;
    private boolean globalBossbar = true;
    private boolean globalTab = true;
    private boolean globalHologram = true;
    private boolean joinMessage = true;
    private BigDecimal personalNoteCoverage = BigDecimal.ONE;
    private long clock;

    private BankBooks(Map<String, CurrencyDef> currencies) {
        this.currencies = currencies;
    }

    public static BankBooks create(BankConfig config) {
        config.validate();
        BankBooks books = new BankBooks(new LinkedHashMap<>(config.asMap()));
        books.numeraire = config.numeraire();
        books.feeFraction = config.feeFraction();
        books.clampMin = config.clampMin();
        books.clampMax = config.clampMax();
        books.globalSidebar = config.sidebar();
        books.globalBossbar = config.bossbar();
        books.globalTab = config.tab();
        books.globalHologram = config.hologram();
        books.joinMessage = config.joinMessage();
        for (CurrencyDef def : config.currencies()) {
            books.alphas.put(def.id(), def.alphaPermille());
            books.supplies.put(def.id(), new Supply(
                    def.id(), def.initialReserve(), def.initialLiability(), def.initialLiability(), BigInteger.ZERO));
        }
        return books;
    }

    public TxResult deposit(UUID player, String currency, BigInteger amount) {
        CurrencyDef def = need(currency);
        if (def == null) {
            return TxResult.fail("unknown_currency", "Unknown currency");
        }
        if (def.kind() != CurrencyKind.COMMODITY) {
            return TxResult.fail("not_commodity", "Deposit bank-note paper through the note counter, not as a commodity.");
        }
        if (amount == null || amount.signum() <= 0) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        creditBalance(player, currency, amount);
        supply(currency).addReserve(amount);
        supply(currency).addLiability(amount);
        return TxResult.ok(amount, "Deposited " + label(def) + " " + CompactFormat.format(amount));
    }

    public TxResult withdraw(UUID player, String currency, BigInteger amount, boolean partial) {
        CurrencyDef def = need(currency);
        if (def == null) {
            return TxResult.fail("unknown_currency", "Unknown currency");
        }
        if (def.kind() != CurrencyKind.COMMODITY) {
            return TxResult.fail("not_commodity", "Notes are withdrawn as stackable paper, not as a commodity pile.");
        }
        if (amount == null || amount.signum() <= 0) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        if ("diamond".equals(currency)) {
            return withdrawDiamond(player, amount, partial);
        }
        BigInteger bal = balance(player, currency);
        BigInteger free = supply(currency).freeReserve();
        BigInteger give = amount;
        if (partial) {
            give = amount.min(bal).min(free).min(MAX_PHYSICAL);
            if (give.signum() <= 0) {
                if (bal.signum() <= 0) {
                    return TxResult.fail("insufficient_funds", "Not enough balance");
                }
                return TxResult.fail("insufficient_reserves", "The vault doesn't have enough to pay this out");
            }
        } else {
            if (amount.compareTo(MAX_PHYSICAL) > 0) {
                return TxResult.fail("too_many_items", "You can withdraw at most " + MAX_PHYSICAL + " at once. Issue a bill for more.");
            }
            if (bal.compareTo(amount) < 0) {
                return TxResult.fail("insufficient_funds", "Not enough balance");
            }
            if (free.compareTo(amount) < 0) {
                return TxResult.fail("insufficient_reserves", "The vault doesn't have enough to pay this out");
            }
        }
        debitBalance(player, currency, give);
        supply(currency).addReserve(give.negate());
        supply(currency).addLiability(give.negate());
        String extra = partial && give.compareTo(amount) < 0 ? " (short: vault or stack limit)" : "";
        return TxResult.ok(give, "Withdrew " + label(def) + " " + CompactFormat.format(give) + extra);
    }

    /**
     * Pays diamonds from the vault, and the missing or fractional part in copper at
     * {@link #copperPerDiamond()} (1,000 copper per diamond when the books are freshly opened).
     */
    private TxResult withdrawDiamond(UUID player, BigInteger amount, boolean partial) {
        if (!partial && amount.compareTo(MAX_PHYSICAL) > 0) {
            return TxResult.fail("too_many_items", "You can withdraw at most " + MAX_PHYSICAL + " at once. Issue a bill for more.");
        }
        BigInteger bal = balance(player, "diamond");
        BigInteger want = partial ? amount.min(bal).min(MAX_PHYSICAL) : amount;
        if (want.signum() <= 0 || (!partial && bal.compareTo(amount) < 0)) {
            return TxResult.fail("insufficient_funds", "Not enough balance");
        }
        BigInteger diamonds = want.min(supply("diamond").freeReserve());
        BigInteger shortfall = want.subtract(diamonds);
        BigInteger rate = copperPerDiamond();
        BigInteger copper = shortfall.multiply(rate);
        if (copper.compareTo(bankStock("copper")) > 0) {
            if (!partial) {
                return TxResult.fail("insufficient_reserves", "The vault doesn't have enough to pay this out");
            }
            BigInteger affordable = bankStock("copper").divide(rate);
            copper = affordable.multiply(rate);
            shortfall = affordable;
            want = diamonds.add(shortfall);
            if (want.signum() <= 0) {
                return TxResult.fail("insufficient_reserves", "The vault doesn't have enough to pay this out");
            }
        }
        debitBalance(player, "diamond", want);
        supply("diamond").addReserve(diamonds.negate());
        supply("diamond").addLiability(want.negate());
        if (copper.signum() > 0) {
            payCopperFromVault(copper);
        }
        return TxResult.ok(diamonds, copper, "Withdrew diamonds " + CompactFormat.format(diamonds)
                + (copper.signum() > 0 ? " change copper " + CompactFormat.format(copper) : ""));
    }

    /**
     * Moves central-note balance onto stackable paper. Liability stays put; the paper is still a claim.
     * At most {@link #MAX_PHYSICAL} unit sheets. A larger amount is one sheet of that face.
     */
    public PaperTake issueCentralPaper(UUID player, BigInteger amount) {
        CurrencyDef note = primaryVirtual();
        if (note == null || note.kind() != CurrencyKind.VIRTUAL) {
            return PaperTake.fail("unknown_currency", "No central note");
        }
        if (amount == null || amount.signum() <= 0) {
            return PaperTake.fail("bad_amount", "Invalid amount");
        }
        if (balance(player, note.id()).compareTo(amount) < 0) {
            return PaperTake.fail("insufficient_funds", "Not enough balance");
        }
        NoteSeries.PaperSlip slip = NoteSeries.plan(amount);
        debitBalance(player, note.id(), slip.value());
        addPaper(NoteSeries.key(note.id(), null, slip.face()), slip.sheets());
        return PaperTake.ok(slip.value(), slip.face(), slip.sheets(), "Withdrew bank-note paper");
    }

    /** Moves a personal-note holding onto stackable paper. Circulation and the lock do not change. */
    public PaperTake issuePersonalPaper(UUID player, UUID noteId, BigInteger amount) {
        PersonalNote note = personalNotes.get(noteId);
        if (note == null) {
            return PaperTake.fail("none", "No personal note");
        }
        if (amount == null || amount.signum() <= 0) {
            return PaperTake.fail("bad_amount", "Invalid amount");
        }
        if (personalHolding(noteId, player).compareTo(amount) < 0) {
            return PaperTake.fail("insufficient_funds", "Not enough personal notes");
        }
        NoteSeries.PaperSlip slip = NoteSeries.plan(amount);
        addHolding(noteId, player, slip.value().negate());
        addPaper(NoteSeries.key(null, noteId, slip.face()), slip.sheets());
        return PaperTake.ok(slip.value(), slip.face(), slip.sheets(), "Withdrew personal-note paper");
    }

    /**
     * Puts paper back. Pays at most the sheets still outstanding, so a split cannot mint value
     * and a copy pays nothing once the series is exhausted. The caller confiscates the whole pile.
     */
    public TxResult redeemPaper(UUID player, PresentedNote presented, BigInteger sheets) {
        if (presented == null || presented.face() == null || presented.face().signum() <= 0
                || sheets == null || sheets.signum() <= 0) {
            return TxResult.fail("note_tampered", "Paper doesn't match the books");
        }
        if (presented.central()) {
            CurrencyDef def = need(presented.currency());
            if (def == null || def.kind() != CurrencyKind.VIRTUAL) {
                return TxResult.fail("note_tampered", "Paper doesn't match the books");
            }
        } else if (!personalNotes.containsKey(presented.personalNote())) {
            return TxResult.fail("note_tampered", "Paper doesn't match the books");
        }
        String key = NoteSeries.key(presented);
        BigInteger out = notePaper.getOrDefault(key, BigInteger.ZERO);
        if (out.signum() <= 0) {
            return TxResult.fail("note_spent", "These notes were already deposited or are copies");
        }
        BigInteger accept = sheets.min(out);
        addPaper(key, accept.negate());
        BigInteger value = accept.multiply(presented.face());
        if (presented.central()) {
            creditBalance(player, presented.currency(), value);
        } else {
            addHolding(presented.personalNote(), player, value);
        }
        boolean trimmed = accept.compareTo(sheets) < 0;
        return new TxResult(true, trimmed ? "trimmed" : "ok", "Deposited bank-note paper", value, null, BigInteger.ZERO);
    }

    public BigInteger paperOutstanding(String currency, UUID personalNote, BigInteger face) {
        if (face == null || face.signum() <= 0) {
            return BigInteger.ZERO;
        }
        return notePaper.getOrDefault(NoteSeries.key(currency, personalNote, face), BigInteger.ZERO);
    }

    private void addPaper(String key, BigInteger delta) {
        BigInteger next = notePaper.getOrDefault(key, BigInteger.ZERO).add(delta);
        if (next.signum() < 0) {
            throw new IllegalStateException("note paper underflow");
        }
        if (next.signum() == 0) {
            notePaper.remove(key);
        } else {
            notePaper.put(key, next);
        }
    }

    /** Copper the central bank can hand out without touching depositors' savings. */
    private BigInteger bankStock(String currency) {
        Supply self = supply(currency);
        BigInteger stock = self.freeReserve().subtract(claimsOf(currency));
        return stock.signum() < 0 ? BigInteger.ZERO : stock;
    }

    /**
     * A redeemed bank note is paid in diamonds, with copper for a short vault or a fractional diamond.
     * One bank note opens at one diamond, and one diamond opens at 1,000 copper.
     */
    private TxResult cashNotes(UUID player, BigInteger notes) {
        BigDecimal perNote = unitsPer("diamond", "note");
        if (perNote == null) {
            creditBalance(player, "note", notes);
            return TxResult.ok(notes, "Redeemed notes");
        }
        BigDecimal exact = perNote.multiply(new BigDecimal(notes), FxMath.MC);
        BigInteger whole = exact.setScale(0, RoundingMode.FLOOR).toBigInteger();
        BigDecimal fraction = exact.subtract(new BigDecimal(whole));
        BigInteger rate = copperPerDiamond();
        BigInteger copper = fraction.multiply(new BigDecimal(rate), FxMath.MC)
                .setScale(0, RoundingMode.HALF_UP).toBigInteger();
        if (copper.signum() < 0) {
            copper = BigInteger.ZERO;
        }
        BigInteger headroom = supply("diamond").freeReserve().subtract(claimsOf("diamond"));
        if (headroom.signum() < 0) {
            headroom = BigInteger.ZERO;
        }
        if (whole.compareTo(headroom) > 0) {
            copper = copper.add(whole.subtract(headroom).multiply(rate));
            whole = headroom;
        }
        supply("note").addLiability(notes.negate());
        if (whole.signum() > 0) {
            creditBalance(player, "diamond", whole);
            supply("diamond").addLiability(whole);
        }
        if (copper.signum() > 0) {
            creditBalance(player, "copper", copper);
            supply("copper").addLiability(copper);
        }
        return TxResult.ok(whole, copper, "Redeemed notes for " + CompactFormat.format(whole) + " diamonds"
                + (copper.signum() > 0 ? " change " + CompactFormat.format(copper) + " copper" : ""));
    }

    private void payCopperFromVault(BigInteger copper) {
        Supply self = supply("copper");
        self.addReserve(copper.negate());
        self.setAnchor(self.anchor().subtract(copper));
        self.addLiability(copper.negate());
    }

    public TxResult exchange(UUID player, String payId, String receiveId, BigInteger receiveQty) {
        if (payId.equals(receiveId)) {
            return TxResult.fail("bad_amount", "Can't exchange a currency for itself");
        }
        CurrencyDef pay = need(payId);
        CurrencyDef recv = need(receiveId);
        if (pay == null || recv == null) {
            return TxResult.fail("unknown_currency", "Unknown currency");
        }
        if (receiveQty == null || receiveQty.signum() <= 0) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        if (recv.kind() == CurrencyKind.COMMODITY && receiveQty.compareTo(MAX_PHYSICAL) > 0) {
            return TxResult.fail("too_many_items", "You can exchange at most " + MAX_PHYSICAL + " physical items at once");
        }
        if (frozen && pay.kind() == CurrencyKind.VIRTUAL && recv.kind() == CurrencyKind.COMMODITY) {
            return TxResult.fail("frozen", "Note redemption is halted");
        }
        BigInteger deliver = receiveQty;
        BigInteger copperChange = BigInteger.ZERO;
        if ("diamond".equals(receiveId)) {
            BigInteger claims = claimsOf(receiveId);
            BigInteger headroom = supply(receiveId).freeReserve().subtract(claims);
            if (headroom.signum() < 0) {
                headroom = BigInteger.ZERO;
            }
            deliver = receiveQty.min(headroom);
            copperChange = receiveQty.subtract(deliver).multiply(copperPerDiamond());
        } else if (recv.kind() == CurrencyKind.COMMODITY) {
            BigInteger claims = claimsOf(receiveId);
            BigInteger headroom = supply(receiveId).freeReserve().subtract(claims);
            if (headroom.signum() < 0) {
                headroom = BigInteger.ZERO;
            }
            if (receiveQty.compareTo(headroom) > 0) {
                return TxResult.fail("insufficient_reserves", "The vault can still pay " + CompactFormat.format(headroom) + " " + label(recv));
            }
        }
        BigInteger cost = cost(payId, receiveId, receiveQty);
        if (cost == null) {
            return TxResult.fail("no_market", "That currency has collapsed. No quote.");
        }
        if (balance(player, payId).compareTo(cost) < 0) {
            return TxResult.fail("insufficient_funds", "Not enough balance. Need " + label(pay) + " " + CompactFormat.format(cost));
        }
        debitBalance(player, payId, cost);
        supply(payId).addLiability(cost.negate());
        if (deliver.signum() > 0) {
            creditBalance(player, receiveId, deliver);
            supply(receiveId).addLiability(deliver);
        }
        if (copperChange.signum() > 0) {
            creditBalance(player, "copper", copperChange);
            supply("copper").addLiability(copperChange);
        }
        return TxResult.ok(cost, copperChange, "Exchanged " + CompactFormat.format(cost) + " " + label(pay)
                + " for " + CompactFormat.format(deliver) + " " + label(recv)
                + (copperChange.signum() > 0 ? " change " + CompactFormat.format(copperChange) + " copper" : ""));
    }

    public TxResult issueCheck(UUID player, String currency, BigInteger amount, String issuerName, UUID payee, boolean fromBalance) {
        CurrencyDef def = need(currency);
        if (def == null) {
            return TxResult.fail("unknown_currency", "Unknown currency");
        }
        if (amount == null || amount.signum() <= 0) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        if (fromBalance) {
            if (balance(player, currency).compareTo(amount) < 0) {
                return TxResult.fail("insufficient_funds", "Not enough balance");
            }
            debitBalance(player, currency, amount);
        } else {
            if (def.kind() != CurrencyKind.VIRTUAL || !def.issuable()) {
                return TxResult.fail("not_issuable", "This currency can't be issued as a new bill");
            }
            supply(currency).addLiability(amount);
        }
        CheckRecord record = checks.create(currency, amount, player, issuerName, payee, System.currentTimeMillis());
        return TxResult.okInstrument(amount, "Issued a " + label(def) + " bill for " + CompactFormat.format(amount), record.id());
    }

    public TxResult redeemCheck(UUID player, PresentedCheck presented) {
        CheckBook.RedeemCode code = checks.redeem(player, presented, System.currentTimeMillis());
        return switch (code) {
            case UNKNOWN -> TxResult.fail("invalid_check", "Unrecognized bill");
            case TAMPERED -> TxResult.fail("tampered", "The bill doesn't match the stub");
            case ALREADY -> TxResult.fail("already_redeemed", "This bill was already redeemed");
            case WRONG_PAYEE -> TxResult.fail("wrong_payee", "This bill isn't made out to you");
            case OK -> {
                if ("note".equals(presented.currency())) {
                    yield cashNotes(player, presented.amount());
                }
                creditBalance(player, presented.currency(), presented.amount());
                CurrencyDef def = need(presented.currency());
                String name = def == null ? presented.currency() : label(def);
                yield TxResult.ok(presented.amount(), "Redeemed " + name + " " + CompactFormat.format(presented.amount()));
            }
        };
    }

    public TxResult creditUnbacked(UUID player, String currency, BigInteger amount) {
        CurrencyDef def = need(currency);
        if (def == null) {
            return TxResult.fail("unknown_currency", "Unknown currency");
        }
        if (def.kind() != CurrencyKind.VIRTUAL || !def.issuable()) {
            return TxResult.fail("not_issuable", "This currency can't be issued as credit");
        }
        if (amount == null || amount.signum() <= 0) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        creditBalance(player, currency, amount);
        supply(currency).addLiability(amount);
        return TxResult.ok(amount, "Issued unbacked " + label(def) + " " + CompactFormat.format(amount));
    }

    public TxResult creditBacked(UUID player, String currency, BigInteger amount) {
        CurrencyDef def = need(currency);
        if (def == null) {
            return TxResult.fail("unknown_currency", "Unknown currency");
        }
        if (def.kind() != CurrencyKind.VIRTUAL || !def.issuable()) {
            return TxResult.fail("not_issuable", "This currency can't be issued as credit");
        }
        if (amount == null || amount.signum() <= 0) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        BigInteger room = backedRoom(def);
        if (amount.compareTo(room) > 0) {
            return TxResult.fail("over_cap", "Above the reserve cap. Room left: " + CompactFormat.format(room));
        }
        BigInteger lock = CreditDesk.backingUnitsNeeded(amount, def.parPerBacking(), def.haircut(), def.minReserveRatio());
        Supply vault = supply(def.backing());
        if (vault.freeReserve().compareTo(lock) < 0) {
            return TxResult.fail("insufficient_reserves", "Not enough free reserves to lock");
        }
        vault.addLocked(lock);
        creditBalance(player, currency, amount);
        supply(currency).addLiability(amount);
        supply(currency).addReserve(amount);
        return TxResult.ok(amount, "Issued reserve-backed " + label(def) + " " + CompactFormat.format(amount)
                + ", locked " + CompactFormat.format(lock) + " " + label(need(def.backing())));
    }

    public TxResult retire(UUID player, String currency, BigInteger amount) {
        CurrencyDef def = need(currency);
        if (def == null) {
            return TxResult.fail("unknown_currency", "Unknown currency");
        }
        if (amount == null || amount.signum() <= 0) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        if (balance(player, currency).compareTo(amount) < 0) {
            return TxResult.fail("insufficient_funds", "Not enough balance");
        }
        Supply self = supply(currency);
        BigInteger unbacked = self.liability().subtract(self.reserve());
        if (unbacked.signum() < 0) {
            unbacked = BigInteger.ZERO;
        }
        debitBalance(player, currency, amount);
        self.addLiability(amount.negate());
        if (def.kind() == CurrencyKind.VIRTUAL && amount.compareTo(unbacked) > 0 && def.backing() != null) {
            BigInteger backedBurn = amount.subtract(unbacked);
            if (backedBurn.compareTo(self.reserve()) > 0) {
                backedBurn = self.reserve();
            }
            self.addReserve(backedBurn.negate());
            BigInteger unlock = CreditDesk.backingUnitsNeeded(backedBurn, def.parPerBacking(), def.haircut(), def.minReserveRatio());
            Supply vault = supply(def.backing());
            if (unlock.compareTo(vault.locked()) > 0) {
                unlock = vault.locked();
            }
            vault.addLocked(unlock.negate());
        }
        return TxResult.ok(amount, "Retired " + label(def) + " " + CompactFormat.format(amount));
    }

    public BigInteger backedRoom(CurrencyDef def) {
        if (def.kind() != CurrencyKind.VIRTUAL || def.backing() == null) {
            return BigInteger.ZERO;
        }
        BigInteger max = CreditDesk.maxLiabilities(supply(def.backing()).reserve(), def.parPerBacking(), def.haircut(), def.minReserveRatio());
        return CreditDesk.room(max, supply(def.id()).liability());
    }

    public BigInteger cost(String payId, String receiveId, BigInteger receiveQty) {
        return FxMath.cost(receiveQty, abundance(payId), abundance(receiveId), feeFraction, clampMin, clampMax);
    }

    /** Units of {@code pay} for one unit of {@code receive}. Null when the market has collapsed. */
    public BigDecimal unitsPer(String pay, String receive) {
        return FxMath.quote(abundance(pay), abundance(receive), clampMin, clampMax);
    }

    public BigDecimal abundance(String id) {
        CurrencyDef def = need(id);
        if (def == null) {
            return null;
        }
        Supply self = supply(id);
        BigDecimal alpha = alphaOf(id);
        if (def.kind() == CurrencyKind.COMMODITY) {
            return FxMath.commodityAbundance(self.reserve(), self.liability(), alpha);
        }
        BigDecimal scale = FxMath.virtualScale(self.reserve(), self.liability(), alpha);
        if (scale == null || def.backing() == null) {
            return null;
        }
        BigDecimal backingAbundance = abundance(def.backing());
        if (backingAbundance == null || backingAbundance.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal worse = scale;
        BigDecimal alphaFraction = alpha.divide(FxMath.THOUSAND, FxMath.MC);
        // Vault pressure uses the same alpha weight. Alpha 0 keeps the par peg even if the
        // cupboard is bare (convertibility still fails on withdraw); alpha 1000 lets an
        // empty diamond vault or a bloated note issue blow the quote out.
        if (self.liability().signum() > 0 && alphaFraction.signum() > 0) {
            BigDecimal vault = new BigDecimal(supply(def.backing()).reserve())
                    .multiply(def.haircut(), FxMath.MC)
                    .multiply(def.parPerBacking(), FxMath.MC);
            if (vault.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            BigDecimal coverScale = new BigDecimal(self.liability()).divide(vault, FxMath.MC);
            BigDecimal pressure = BigDecimal.ONE.subtract(alphaFraction, FxMath.MC)
                    .add(alphaFraction.multiply(coverScale, FxMath.MC), FxMath.MC);
            if (pressure.compareTo(worse) > 0) {
                worse = pressure;
            }
        }
        if (worse.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return backingAbundance.multiply(def.parPerBacking(), FxMath.MC).multiply(worse, FxMath.MC);
    }

    public BigDecimal reserveRatio(String id) {
        Supply self = supplies.get(id);
        if (self == null || self.liability().signum() <= 0) {
            return null;
        }
        return new BigDecimal(self.reserve()).divide(new BigDecimal(self.liability()), FxMath.MC);
    }

    /** Par value of the backing vault divided by virtual liabilities. Null when there is no backing. */
    public BigDecimal vaultCover(String id) {
        CurrencyDef def = need(id);
        if (def == null || def.kind() != CurrencyKind.VIRTUAL || def.backing() == null) {
            return null;
        }
        Supply self = supply(id);
        if (self.liability().signum() <= 0) {
            return null;
        }
        BigDecimal vault = new BigDecimal(supply(def.backing()).reserve())
                .multiply(def.haircut(), FxMath.MC)
                .multiply(def.parPerBacking(), FxMath.MC);
        return vault.divide(new BigDecimal(self.liability()), FxMath.MC);
    }

    public BigDecimal tightness(String id) {
        BigDecimal accounting = reserveRatio(id);
        BigDecimal cover = vaultCover(id);
        if (accounting == null) {
            return cover;
        }
        if (cover == null) {
            return accounting;
        }
        return accounting.min(cover);
    }

    public String statusLabel() {
        CurrencyDef note = primaryVirtual();
        if (note == null) {
            return frozen ? "暂停兑付" : "兑付正常";
        }
        if (abundance(note.id()) == null) {
            return "无法兑付";
        }
        if (frozen) {
            return "暂停兑付";
        }
        BigDecimal tight = tightness(note.id());
        if (tight == null) {
            return "兑付正常";
        }
        if (tight.compareTo(new BigDecimal("0.05")) < 0) {
            return "无法兑付";
        }
        if (tight.compareTo(new BigDecimal("0.25")) < 0) {
            return "准备金不足";
        }
        if (tight.compareTo(new BigDecimal("0.80")) < 0) {
            return "准备金紧张";
        }
        return "兑付正常";
    }

    /** English status for {@code /bank supply}. Displays keep {@link #statusLabel()}. */
    public String statusLabelEn() {
        CurrencyDef note = primaryVirtual();
        if (note == null) {
            return frozen ? "redemption suspended" : "redemption available";
        }
        if (abundance(note.id()) == null) {
            return "unable to redeem";
        }
        if (frozen) {
            return "redemption suspended";
        }
        BigDecimal tight = tightness(note.id());
        if (tight == null) {
            return "redemption available";
        }
        if (tight.compareTo(new BigDecimal("0.05")) < 0) {
            return "unable to redeem";
        }
        if (tight.compareTo(new BigDecimal("0.25")) < 0) {
            return "reserves insufficient";
        }
        if (tight.compareTo(new BigDecimal("0.80")) < 0) {
            return "reserves strained";
        }
        return "redemption available";
    }

    private static String label(CurrencyDef def) {
        if (def == null) {
            return "?";
        }
        String en = def.nameEn();
        if (en == null || en.isBlank()) {
            return def.id();
        }
        return en;
    }

    public CurrencyDef primaryVirtual() {
        CurrencyDef first = null;
        for (CurrencyDef def : currencies.values()) {
            if (def.kind() != CurrencyKind.VIRTUAL) {
                continue;
            }
            if (first == null) {
                first = def;
            }
            if (def.issuable()) {
                return def;
            }
        }
        return first;
    }

    public BigInteger balance(UUID player, String currency) {
        Map<String, BigInteger> map = accounts.get(player);
        if (map == null) {
            return BigInteger.ZERO;
        }
        return map.getOrDefault(currency, BigInteger.ZERO);
    }

    public Supply supply(String currency) {
        Supply supply = supplies.get(currency);
        if (supply == null) {
            throw new IllegalArgumentException("Unknown currency: " + currency);
        }
        return supply;
    }

    public Collection<CurrencyDef> currencies() {
        return currencies.values();
    }

    public CurrencyDef currency(String id) {
        return currencies.get(id);
    }

    public CurrencyDef byItem(String itemId) {
        for (CurrencyDef def : currencies.values()) {
            if (def.kind() == CurrencyKind.COMMODITY && itemId.equals(def.itemId())) {
                return def;
            }
        }
        return null;
    }

    public String numeraire() { return numeraire; }
    public BigDecimal feeFraction() { return feeFraction; }
    public BigDecimal clampMin() { return clampMin; }
    public BigDecimal clampMax() { return clampMax; }
    public boolean frozen() { return frozen; }
    public boolean globalSidebar() { return globalSidebar; }
    public boolean globalBossbar() { return globalBossbar; }
    public boolean globalTab() { return globalTab; }
    public boolean globalHologram() { return globalHologram; }
    public boolean joinMessage() { return joinMessage; }
    public BigDecimal alphaOf(String id) { return alphas.getOrDefault(id, new BigDecimal("1000")); }

    public void setFeeFraction(BigDecimal feeFraction) { this.feeFraction = feeFraction; }
    public void setAlpha(String id, BigDecimal permille) { this.alphas.put(id, permille); }
    public void setClampMin(BigDecimal clampMin) { this.clampMin = clampMin; }
    public void setClampMax(BigDecimal clampMax) { this.clampMax = clampMax; }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }
    public void setGlobalSidebar(boolean v) { this.globalSidebar = v; }
    public void setGlobalBossbar(boolean v) { this.globalBossbar = v; }
    public void setGlobalTab(boolean v) { this.globalTab = v; }
    public void setGlobalHologram(boolean v) { this.globalHologram = v; }
    public void setJoinMessage(boolean v) { this.joinMessage = v; }

    /**
     * Sets the opening stock of one currency. Player savings and outstanding bills stay.
     * The vault moves by the same amount as the opening anchor, and liabilities stay
     * {@code anchor + claims}, so the quote follows the new books instead of a leftover seed.
     */
    public void setOpeningReserve(String currency, BigInteger opening) {
        if (need(currency) == null) {
            throw new IllegalArgumentException("unknown");
        }
        if (opening == null || opening.signum() < 0) {
            throw new IllegalArgumentException("negative");
        }
        Supply self = supply(currency);
        BigInteger claims = claimsOf(currency);
        BigInteger newReserve = self.reserve().add(opening.subtract(self.anchor()));
        if (newReserve.signum() < 0) {
            throw new IllegalArgumentException("negative");
        }
        if (newReserve.compareTo(self.locked()) < 0) {
            throw new IllegalArgumentException("below_locked");
        }
        self.setAnchor(opening);
        self.setLiability(opening.add(claims));
        self.setReserve(newReserve);
    }

    /** Raw vault stock. Does not move the opening anchor. `/bank reserve` uses {@link #setOpeningReserve}. */
    public void setReserve(String currency, BigInteger reserve) {
        if (need(currency) == null) {
            throw new IllegalArgumentException("unknown");
        }
        if (reserve == null || reserve.signum() < 0) {
            throw new IllegalArgumentException("negative");
        }
        Supply self = supply(currency);
        if (reserve.compareTo(self.locked()) < 0) {
            throw new IllegalArgumentException("below_locked");
        }
        self.setReserve(reserve);
    }

    /** Whole copper ingots for one diamond. Opens at 1000; follows the live quote when there is one. */
    public BigInteger copperPerDiamond() {
        BigDecimal rate = unitsPer("copper", "diamond");
        if (rate == null || rate.signum() <= 0) {
            return BigInteger.valueOf(1000);
        }
        BigInteger rounded = rate.setScale(0, RoundingMode.HALF_UP).toBigInteger();
        return rounded.signum() < 1 ? BigInteger.ONE : rounded;
    }

    public BigInteger personalReserve(UUID player, String currency) {
        Map<String, BigInteger> row = personalReserves.get(player);
        if (row == null) {
            return BigInteger.ZERO;
        }
        return row.getOrDefault(currency, BigInteger.ZERO);
    }

    public Map<String, BigInteger> personalReservesOf(UUID player) {
        Map<String, BigInteger> row = personalReserves.get(player);
        if (row == null) {
            return Map.of();
        }
        return Map.copyOf(row);
    }

    /** Stores a personal issuance reserve. Does not touch savings balances or central supplies. */
    public void setPersonalReserve(UUID player, String currency, BigInteger amount) {
        if (need(currency) == null) {
            throw new IllegalArgumentException("unknown");
        }
        if (player == null || amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("negative");
        }
        Map<String, BigInteger> row = personalReserves.computeIfAbsent(player, id -> new LinkedHashMap<>());
        if (amount.signum() == 0) {
            row.remove(currency);
            if (row.isEmpty()) {
                personalReserves.remove(player);
            }
            return;
        }
        row.put(currency, amount);
    }

    public BigDecimal personalNoteCoverage() { return personalNoteCoverage; }

    public void setPersonalNoteCoverage(BigDecimal coverage) {
        if (coverage == null || coverage.signum() <= 0 || coverage.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("coverage");
        }
        this.personalNoteCoverage = coverage;
    }

    public java.util.Collection<PersonalNote> personalNotes() {
        return personalNotes.values();
    }

    public PersonalNote personalNoteById(UUID id) {
        return id == null ? null : personalNotes.get(id);
    }

    public PersonalNote personalNote(UUID issuer) {
        for (PersonalNote note : personalNotes.values()) {
            if (note.issuer().equals(issuer)) {
                return note;
            }
        }
        return null;
    }

    public PersonalNote personalNoteByName(String name) {
        if (name == null) {
            return null;
        }
        String key = PersonalMarket.canonicalName(name);
        for (PersonalNote note : personalNotes.values()) {
            if (PersonalMarket.canonicalName(note.name()).equals(key)) {
                return note;
            }
        }
        return null;
    }

    public BigInteger personalHolding(UUID noteId, UUID player) {
        Map<UUID, BigInteger> row = personalHoldings.get(noteId);
        if (row == null) {
            return BigInteger.ZERO;
        }
        return row.getOrDefault(player, BigInteger.ZERO);
    }

    public BigDecimal personalDiamondPrice(PersonalNote note) {
        return PersonalMarket.diamondPrice(note, copperPerDiamond(), effectiveBacking(note));
    }

    public BigInteger effectiveBacking(PersonalNote note) {
        String backing = note.copperPar() ? "copper" : "diamond";
        return personalReserve(note.issuer(), backing).min(note.locked());
    }

    public NoteTrade createPersonalNote(UUID issuer, String name, boolean copperPar, BigInteger par) {
        if (!PersonalMarket.validName(name)) {
            return NoteTrade.fail("name");
        }
        if (personalNote(issuer) != null) {
            return NoteTrade.fail("exists");
        }
        if (personalNoteByName(name) != null) {
            return NoteTrade.fail("taken");
        }
        if (par == null || par.signum() <= 0) {
            return NoteTrade.fail("par");
        }
        PersonalNote note = new PersonalNote(UUID.randomUUID(), issuer, name, copperPar, par);
        personalNotes.put(note.id(), note);
        return NoteTrade.ok(note, BigInteger.ZERO, BigInteger.ZERO);
    }

    public NoteTrade issuePersonalNote(UUID issuer, BigInteger amount) {
        PersonalNote note = personalNote(issuer);
        if (note == null) {
            return NoteTrade.fail("none");
        }
        if (amount == null || amount.signum() <= 0) {
            return NoteTrade.fail("par");
        }
        BigInteger next = note.circulation().add(amount);
        BigInteger required = requiredLock(note, next);
        String backing = note.copperPar() ? "copper" : "diamond";
        if (personalReserve(issuer, backing).compareTo(required) < 0) {
            return NoteTrade.fail("reserve");
        }
        note.setLocked(required);
        note.setCirculation(next);
        addHolding(note.id(), issuer, amount);
        return NoteTrade.ok(note, BigInteger.ZERO, BigInteger.ZERO);
    }

    public NoteTrade buyPersonalNote(UUID buyer, String name, BigInteger amount) {
        return tradePersonalNote(buyer, name, amount, true);
    }

    public NoteTrade sellPersonalNote(UUID seller, String name, BigInteger amount) {
        return tradePersonalNote(seller, name, amount, false);
    }

    private NoteTrade tradePersonalNote(UUID actor, String name, BigInteger amount, boolean buy) {
        PersonalNote note = personalNoteByName(name);
        if (note == null) {
            return NoteTrade.fail("none");
        }
        if (amount == null || amount.signum() <= 0) {
            return NoteTrade.fail("par");
        }
        if (actor.equals(note.issuer())) {
            return NoteTrade.fail("self");
        }
        if (unitsPer("note", "diamond") == null) {
            return NoteTrade.fail("market");
        }
        BigDecimal diamondEach = personalDiamondPrice(note);
        BigDecimal diamonds = diamondEach.multiply(new BigDecimal(amount), FxMath.MC);
        BigDecimal notesPerDiamond = unitsPer("note", "diamond");
        BigDecimal exactCentral = diamonds.multiply(notesPerDiamond, FxMath.MC);
        BigInteger central = exactCentral.setScale(0, RoundingMode.FLOOR).toBigInteger();
        BigDecimal fraction = exactCentral.subtract(new BigDecimal(central));
        BigInteger copper = fraction.multiply(new BigDecimal(copperPerDiamond()), FxMath.MC)
                .setScale(0, RoundingMode.HALF_UP).toBigInteger();
        if (copper.signum() < 0) {
            copper = BigInteger.ZERO;
        }
        UUID payer = buy ? actor : note.issuer();
        UUID payee = buy ? note.issuer() : actor;
        UUID noteSource = buy ? note.issuer() : actor;
        UUID noteDest = buy ? actor : note.issuer();
        if (personalHolding(note.id(), noteSource).compareTo(amount) < 0) {
            return NoteTrade.fail("empty");
        }
        if (balance(payer, "note").compareTo(central) < 0 || balance(payer, "copper").compareTo(copper) < 0) {
            return NoteTrade.fail("funds");
        }
        if (central.signum() > 0) {
            debitBalance(payer, "note", central);
            creditBalance(payee, "note", central);
        }
        if (copper.signum() > 0) {
            debitBalance(payer, "copper", copper);
            creditBalance(payee, "copper", copper);
        }
        addHolding(note.id(), noteSource, amount.negate());
        addHolding(note.id(), noteDest, amount);
        if (buy) {
            note.addBuy(amount);
        } else {
            note.addSell(amount);
        }
        return NoteTrade.ok(note, central, copper);
    }

    /**
     * Swap {@code receiveAmount} of {@code receiveName} for whole notes of {@code payName}
     * at {@code P_receive / P_pay}. The remainder is paid in central notes and copper.
     * Holdings move between the player and a counterparty. Issuer reserves stay put.
     * The issuer cannot take the other side of their own note.
     */
    public SwapTrade swapPersonalNotes(UUID player, String payName, String receiveName, BigInteger receiveAmount) {
        PersonalNote pay = personalNoteByName(payName);
        PersonalNote receive = personalNoteByName(receiveName);
        if (pay == null || receive == null) {
            return SwapTrade.fail("none");
        }
        if (pay.id().equals(receive.id())) {
            return SwapTrade.fail("pair");
        }
        if (receiveAmount == null || receiveAmount.signum() <= 0) {
            return SwapTrade.fail("par");
        }
        if (player.equals(receive.issuer())) {
            return SwapTrade.fail("self");
        }
        BigDecimal notesPerDiamond = unitsPer("note", "diamond");
        if (notesPerDiamond == null) {
            return SwapTrade.fail("market");
        }
        SecondaryClearing.Quote quote = SecondaryClearing.priceSwap(
                personalDiamondPrice(pay), personalDiamondPrice(receive), receiveAmount,
                notesPerDiamond, copperPerDiamond());
        if (quote == null) {
            return SwapTrade.fail("quote");
        }
        UUID counterparty = noteCounterparty(receive, receiveAmount, player);
        if (counterparty == null) {
            return SwapTrade.fail("empty");
        }
        if (personalHolding(pay.id(), player).compareTo(quote.payNotes()) < 0) {
            return SwapTrade.fail("empty");
        }
        if (balance(player, "note").compareTo(quote.central()) < 0
                || balance(player, "copper").compareTo(quote.copper()) < 0) {
            return SwapTrade.fail("funds");
        }
        addHolding(pay.id(), player, quote.payNotes().negate());
        addHolding(pay.id(), counterparty, quote.payNotes());
        addHolding(receive.id(), counterparty, receiveAmount.negate());
        addHolding(receive.id(), player, receiveAmount);
        if (quote.central().signum() > 0) {
            debitBalance(player, "note", quote.central());
            creditBalance(counterparty, "note", quote.central());
        }
        if (quote.copper().signum() > 0) {
            debitBalance(player, "copper", quote.copper());
            creditBalance(counterparty, "copper", quote.copper());
        }
        receive.addBuy(receiveAmount);
        pay.addSell(quote.payNotes());
        return SwapTrade.ok(pay, receive, quote.payNotes(), receiveAmount, quote.central(), quote.copper());
    }

    /**
     * Universal redemption at the live diamond quote. Retires circulation, unlocks the
     * posted reserve, and pays the player from that reserve. A short reserve pays only
     * what is posted (an implied discount) and never more.
     */
    public Redemption redeemPersonal(UUID player, String name, BigInteger amount, String payout) {
        PersonalNote note = personalNoteByName(name);
        if (note == null) {
            return Redemption.fail("none");
        }
        if (amount == null || amount.signum() <= 0) {
            return Redemption.fail("par");
        }
        if (!validPayout(payout)) {
            return Redemption.fail("payout");
        }
        if (personalHolding(note.id(), player).compareTo(amount) < 0) {
            return Redemption.fail("empty");
        }
        Redemption priced = priceRedemption(note, amount, payout);
        if (!priced.success()) {
            return priced;
        }
        addHolding(note.id(), player, amount.negate());
        applyRedemption(player, note, amount, priced);
        return priced;
    }

    /** Same redemption, burning outstanding paper instead of a book-entry holding. */
    public Redemption redeemPersonalPaper(UUID player, UUID noteId, BigInteger face, BigInteger sheets, String payout) {
        PersonalNote note = personalNotes.get(noteId);
        if (note == null || face == null || face.signum() <= 0 || sheets == null || sheets.signum() <= 0) {
            return Redemption.fail("note_tampered");
        }
        if (!validPayout(payout)) {
            return Redemption.fail("payout");
        }
        BigInteger outstanding = paperOutstanding(null, noteId, face);
        if (outstanding.signum() <= 0) {
            return Redemption.fail("note_spent");
        }
        BigInteger acceptSheets = sheets.min(outstanding);
        BigInteger amount = face.multiply(acceptSheets);
        if (amount.compareTo(note.circulation()) > 0) {
            amount = note.circulation();
        }
        if (amount.signum() <= 0) {
            return Redemption.fail("note_spent");
        }
        Redemption priced = priceRedemption(note, amount, payout);
        if (!priced.success()) {
            return priced;
        }
        addPaper(NoteSeries.key(null, noteId, face), acceptSheets.negate());
        applyRedemption(player, note, amount, priced);
        return priced;
    }

    private Redemption priceRedemption(PersonalNote note, BigInteger amount, String payout) {
        BigDecimal price = personalDiamondPrice(note);
        BigInteger rate = copperPerDiamond();
        BigDecimal diamondValue = price.multiply(new BigDecimal(amount), FxMath.MC);
        BigDecimal parCost = note.copperPar()
                ? diamondValue.multiply(new BigDecimal(rate), FxMath.MC)
                : diamondValue;
        BigInteger available = effectiveBacking(note);
        if (available.signum() <= 0 || parCost.signum() <= 0) {
            return Redemption.fail("reserve");
        }
        boolean discounted = parCost.compareTo(new BigDecimal(available)) > 0;
        BigInteger consumed = discounted
                ? available
                : parCost.setScale(0, RoundingMode.DOWN).toBigInteger();
        if (consumed.signum() <= 0 || consumed.compareTo(available) > 0) {
            return Redemption.fail("reserve");
        }
        BigDecimal paidDiamonds = note.copperPar()
                ? new BigDecimal(consumed).divide(new BigDecimal(rate), FxMath.MC)
                : new BigDecimal(consumed);
        BigInteger diamonds = BigInteger.ZERO;
        BigInteger copper = BigInteger.ZERO;
        BigInteger central = BigInteger.ZERO;
        switch (payout) {
            case "diamond" -> {
                diamonds = paidDiamonds.setScale(0, RoundingMode.DOWN).toBigInteger();
                if (diamonds.signum() < 0) {
                    diamonds = BigInteger.ZERO;
                }
            }
            case "copper" -> {
                copper = paidDiamonds.multiply(new BigDecimal(rate), FxMath.MC)
                        .setScale(0, RoundingMode.HALF_UP).toBigInteger();
                if (copper.signum() < 0) {
                    copper = BigInteger.ZERO;
                }
            }
            case "note" -> {
                BigDecimal notesPerDiamond = unitsPer("note", "diamond");
                if (notesPerDiamond == null || notesPerDiamond.signum() <= 0) {
                    return Redemption.fail("market");
                }
                BigDecimal exact = paidDiamonds.multiply(notesPerDiamond, FxMath.MC);
                central = exact.setScale(0, RoundingMode.FLOOR).toBigInteger();
                if (central.signum() < 0) {
                    central = BigInteger.ZERO;
                }
                copper = exact.subtract(new BigDecimal(central))
                        .multiply(new BigDecimal(rate), FxMath.MC)
                        .setScale(0, RoundingMode.HALF_UP).toBigInteger();
                if (copper.signum() < 0) {
                    copper = BigInteger.ZERO;
                }
            }
            default -> {
                return Redemption.fail("payout");
            }
        }
        if (diamonds.signum() == 0 && copper.signum() == 0 && central.signum() == 0) {
            return Redemption.fail("reserve");
        }
        return new Redemption(true, discounted ? "discounted" : "ok", note, amount, diamonds, copper, central, discounted);
    }

    private void applyRedemption(UUID player, PersonalNote note, BigInteger amount, Redemption priced) {
        String parCurrency = note.copperPar() ? "copper" : "diamond";
        BigInteger available = effectiveBacking(note);
        BigInteger consumed = priced.discounted()
                ? available
                : parConsumed(note, amount, available);
        if (consumed.compareTo(available) > 0) {
            consumed = available;
        }
        note.setCirculation(note.circulation().subtract(amount));
        BigInteger nextReserve = personalReserve(note.issuer(), parCurrency).subtract(consumed);
        if (nextReserve.signum() < 0) {
            nextReserve = BigInteger.ZERO;
        }
        setPersonalReserve(note.issuer(), parCurrency, nextReserve);
        BigInteger relock = requiredLock(note, note.circulation());
        if (relock.compareTo(nextReserve) > 0) {
            relock = nextReserve;
        }
        note.setLocked(relock);
        payRedemption(player, priced);
    }

    private BigInteger parConsumed(PersonalNote note, BigInteger amount, BigInteger available) {
        BigDecimal price = personalDiamondPrice(note);
        BigDecimal diamondValue = price.multiply(new BigDecimal(amount), FxMath.MC);
        BigDecimal parCost = note.copperPar()
                ? diamondValue.multiply(new BigDecimal(copperPerDiamond()), FxMath.MC)
                : diamondValue;
        BigInteger consumed = parCost.setScale(0, RoundingMode.DOWN).toBigInteger();
        if (consumed.signum() <= 0) {
            return BigInteger.ZERO;
        }
        return consumed.min(available);
    }

    private void payRedemption(UUID player, Redemption priced) {
        creditCommodity(player, "diamond", priced.diamonds());
        creditCommodity(player, "copper", priced.copper());
        if (priced.central().signum() > 0) {
            creditBalance(player, "note", priced.central());
            supply("note").addLiability(priced.central());
        }
    }

    private void creditCommodity(UUID player, String currency, BigInteger amount) {
        if (amount == null || amount.signum() <= 0 || need(currency) == null) {
            return;
        }
        creditBalance(player, currency, amount);
        supply(currency).addLiability(amount);
        if (need(currency).kind() == CurrencyKind.COMMODITY) {
            supply(currency).addReserve(amount);
        }
    }

    private static boolean validPayout(String payout) {
        return "diamond".equals(payout) || "copper".equals(payout) || "note".equals(payout);
    }

    private UUID noteCounterparty(PersonalNote note, BigInteger amount, UUID actor) {
        if (!actor.equals(note.issuer()) && personalHolding(note.id(), note.issuer()).compareTo(amount) >= 0) {
            return note.issuer();
        }
        Map<UUID, BigInteger> row = personalHoldings.get(note.id());
        if (row == null) {
            return null;
        }
        for (Map.Entry<UUID, BigInteger> entry : row.entrySet()) {
            if (entry.getKey().equals(actor)) {
                continue;
            }
            if (entry.getValue().compareTo(amount) >= 0) {
                return entry.getKey();
            }
        }
        return null;
    }

    private BigInteger requiredLock(PersonalNote note, BigInteger circulation) {
        BigDecimal raw = new BigDecimal(note.par().multiply(circulation)).multiply(personalNoteCoverage, FxMath.MC);
        BigInteger required = raw.setScale(0, RoundingMode.CEILING).toBigInteger();
        return required.signum() < 0 ? BigInteger.ZERO : required;
    }

    private void addHolding(UUID noteId, UUID player, BigInteger delta) {
        Map<UUID, BigInteger> row = personalHoldings.computeIfAbsent(noteId, id -> new LinkedHashMap<>());
        BigInteger next = row.getOrDefault(player, BigInteger.ZERO).add(delta);
        if (next.signum() < 0) {
            throw new IllegalStateException("personal note underflow");
        }
        if (next.signum() == 0) {
            row.remove(player);
            if (row.isEmpty()) {
                personalHoldings.remove(noteId);
            }
        } else {
            row.put(player, next);
        }
    }

    public boolean showSidebar(UUID player) {
        return globalSidebar && pref(player).sidebar;
    }

    public boolean showBossbar(UUID player) {
        return globalBossbar && pref(player).bossbar;
    }

    public boolean showTab(UUID player) {
        return globalTab && pref(player).tab;
    }

    public void setPref(UUID player, Boolean sidebar, Boolean bossbar, Boolean tab) {
        DisplayPref pref = pref(player);
        if (sidebar != null) {
            pref.sidebar = sidebar;
        }
        if (bossbar != null) {
            pref.bossbar = bossbar;
        }
        if (tab != null) {
            pref.tab = tab;
        }
    }

    public List<OutletPoint> outlets() {
        return outlets;
    }

    public OutletPoint outletByMarker(UUID markerId) {
        if (markerId == null) {
            return null;
        }
        for (OutletPoint outlet : outlets) {
            if (markerId.equals(outlet.markerId())) {
                return outlet;
            }
        }
        return null;
    }

    public OutletPoint outlet(String name) {
        for (OutletPoint outlet : outlets) {
            if (outlet.name().equalsIgnoreCase(name)) {
                return outlet;
            }
        }
        return null;
    }

    public OutletPoint addOutlet(OutletPoint outlet) {
        OutletPoint existing = outlet(outlet.name());
        if (existing != null) {
            outlets.remove(existing);
        }
        outlets.add(outlet);
        return outlet;
    }

    public boolean removeOutlet(String name) {
        return outlets.removeIf(o -> o.name().equalsIgnoreCase(name));
    }

    public OutletPoint outletAtBlock(String world, int x, int y, int z) {
        for (OutletPoint outlet : outlets) {
            if (outlet.sameBlock(world, x, y, z)) {
                return outlet;
            }
        }
        return null;
    }

    public OutletPoint nearest(String world, double x, double y, double z, double extra) {
        OutletPoint best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (OutletPoint outlet : outlets) {
            double d = outlet.distanceSquared(world, x, y, z);
            double limit = outlet.radius() + extra;
            if (d <= limit * limit && d < bestD) {
                best = outlet;
                bestD = d;
            }
        }
        return best;
    }

    public CheckRecord check(UUID id) {
        return checks.get(id);
    }

    public boolean markImported(UUID player) {
        return imported.add(player);
    }

    public boolean alreadyImported(UUID player) {
        return imported.contains(player);
    }

    public void applyPolicy(BankConfig config) {
        config.validate();
        this.feeFraction = config.feeFraction();
        this.clampMin = config.clampMin();
        this.clampMax = config.clampMax();
        for (CurrencyDef def : config.currencies()) {
            currencies.putIfAbsent(def.id(), def);
            alphas.put(def.id(), def.alphaPermille());
            supplies.computeIfAbsent(def.id(), id -> new Supply(
                    id, def.initialReserve(), def.initialLiability(), def.initialLiability(), BigInteger.ZERO));
        }
    }

    public void checkInvariants() {
        Map<String, BigInteger> claims = claimMap();
        for (Supply supply : supplies.values()) {
            BigInteger expected = supply.anchor().add(claims.getOrDefault(supply.currency(), BigInteger.ZERO));
            if (expected.compareTo(supply.liability()) != 0) {
                throw new AssertionError(supply.currency() + " liability " + supply.liability() + " != " + expected);
            }
            if (supply.reserve().signum() < 0 || supply.locked().signum() < 0 || supply.liability().signum() < 0) {
                throw new AssertionError(supply.currency() + " went negative");
            }
            if (supply.locked().compareTo(supply.reserve()) > 0) {
                throw new AssertionError(supply.currency() + " locked exceeds reserve");
            }
        }
        for (Map<String, BigInteger> bals : accounts.values()) {
            for (BigInteger bal : bals.values()) {
                if (bal.signum() < 0) {
                    throw new AssertionError("negative balance");
                }
            }
        }
        for (PersonalNote note : personalNotes.values()) {
            if (holdingTotal(note.id()).add(personalPaperValue(note.id())).compareTo(note.circulation()) != 0) {
                throw new AssertionError("personal note " + note.name() + " holdings and paper diverged from circulation");
            }
        }
        for (BigInteger count : notePaper.values()) {
            if (count.signum() < 0) {
                throw new AssertionError("negative note paper");
            }
        }
    }

    public String audit() {
        StringBuilder sb = new StringBuilder();
        Map<String, BigInteger> claims = claimMap();
        for (CurrencyDef def : currencies.values()) {
            Supply supply = supplies.get(def.id());
            BigInteger expected = supply.anchor().add(claims.getOrDefault(def.id(), BigInteger.ZERO));
            sb.append(def.id())
                    .append(" R=").append(supply.reserve())
                    .append(" L=").append(supply.liability())
                    .append(" anchor=").append(supply.anchor())
                    .append(" locked=").append(supply.locked())
                    .append(" claims=").append(claims.getOrDefault(def.id(), BigInteger.ZERO))
                    .append(" ok=").append(expected.equals(supply.liability()))
                    .append('\n');
        }
        return sb.toString();
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", SNAPSHOT_VERSION);
        root.addProperty("numeraire", numeraire);
        root.addProperty("fee", feeFraction.toPlainString());
        root.addProperty("frozen", frozen);
        root.add("clampMin", clampMin == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(clampMin.toPlainString()));
        root.add("clampMax", clampMax == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(clampMax.toPlainString()));
        root.addProperty("globalSidebar", globalSidebar);
        root.addProperty("globalBossbar", globalBossbar);
        root.addProperty("globalTab", globalTab);
        root.addProperty("globalHologram", globalHologram);
        root.addProperty("joinMessage", joinMessage);
        root.addProperty("clock", clock);

        JsonObject alphaJson = new JsonObject();
        alphas.forEach((k, v) -> alphaJson.addProperty(k, v.toPlainString()));
        root.add("alphas", alphaJson);

        JsonObject currencyJson = new JsonObject();
        for (CurrencyDef def : currencies.values()) {
            currencyJson.add(def.id(), currencyJson(def));
        }
        root.add("currencies", currencyJson);

        JsonObject supplyJson = new JsonObject();
        for (Supply supply : supplies.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("reserve", supply.reserve().toString());
            o.addProperty("liability", supply.liability().toString());
            o.addProperty("anchor", supply.anchor().toString());
            o.addProperty("locked", supply.locked().toString());
            supplyJson.add(supply.currency(), o);
        }
        root.add("supplies", supplyJson);

        JsonObject accountJson = new JsonObject();
        for (Map.Entry<UUID, Map<String, BigInteger>> entry : accounts.entrySet()) {
            JsonObject bals = new JsonObject();
            entry.getValue().forEach((c, v) -> {
                if (v.signum() != 0) {
                    bals.addProperty(c, v.toString());
                }
            });
            if (bals.size() > 0) {
                accountJson.add(entry.getKey().toString(), bals);
            }
        }
        root.add("accounts", accountJson);

        JsonObject personalJson = new JsonObject();
        for (Map.Entry<UUID, Map<String, BigInteger>> entry : personalReserves.entrySet()) {
            JsonObject row = new JsonObject();
            entry.getValue().forEach((c, v) -> {
                if (v.signum() != 0) {
                    row.addProperty(c, v.toString());
                }
            });
            if (row.size() > 0) {
                personalJson.add(entry.getKey().toString(), row);
            }
        }
        root.add("personalReserves", personalJson);
        root.addProperty("personalNoteCoverage", personalNoteCoverage.toPlainString());
        JsonArray noteJson = new JsonArray();
        for (PersonalNote note : personalNotes.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", note.id().toString());
            o.addProperty("issuer", note.issuer().toString());
            o.addProperty("name", note.name());
            o.addProperty("copperPar", note.copperPar());
            o.addProperty("par", note.par().toString());
            o.addProperty("circulation", note.circulation().toString());
            o.addProperty("buys", note.buys().toString());
            o.addProperty("sells", note.sells().toString());
            o.addProperty("locked", note.locked().toString());
            noteJson.add(o);
        }
        root.add("personalNotes", noteJson);
        JsonObject holdingJson = new JsonObject();
        for (Map.Entry<UUID, Map<UUID, BigInteger>> entry : personalHoldings.entrySet()) {
            JsonObject row = new JsonObject();
            entry.getValue().forEach((player, amount) -> {
                if (amount.signum() != 0) {
                    row.addProperty(player.toString(), amount.toString());
                }
            });
            if (row.size() > 0) {
                holdingJson.add(entry.getKey().toString(), row);
            }
        }
        root.add("personalHoldings", holdingJson);

        JsonArray checkJson = new JsonArray();
        for (CheckRecord record : checks.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", record.id().toString());
            o.addProperty("currency", record.currency());
            o.addProperty("amount", record.amount().toString());
            o.addProperty("issuer", record.issuer().toString());
            o.addProperty("issuerName", record.issuerName());
            o.add("payee", record.payee() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(record.payee().toString()));
            o.addProperty("issuedAt", record.issuedAtEpochMs());
            o.addProperty("status", record.status().name());
            o.add("redeemedBy", record.redeemedBy() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(record.redeemedBy().toString()));
            o.addProperty("redeemedAt", record.redeemedAtEpochMs());
            checkJson.add(o);
        }
        root.add("checks", checkJson);

        JsonObject paperJson = new JsonObject();
        notePaper.forEach((key, count) -> {
            if (count.signum() > 0) {
                paperJson.addProperty(key, count.toString());
            }
        });
        root.add("notePaper", paperJson);

        JsonArray outletJson = new JsonArray();
        for (OutletPoint outlet : outlets) {
            JsonObject o = new JsonObject();
            o.addProperty("name", outlet.name());
            o.addProperty("world", outlet.world());
            o.addProperty("x", outlet.x());
            o.addProperty("y", outlet.y());
            o.addProperty("z", outlet.z());
            o.addProperty("radius", outlet.radius());
            o.addProperty("lift", outlet.lift());
            o.addProperty("hologram", outlet.hologram());
            o.add("hologramId", outlet.hologramId() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(outlet.hologramId().toString()));
            o.add("markerId", outlet.markerId() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(outlet.markerId().toString()));
            outletJson.add(o);
        }
        root.add("outlets", outletJson);

        JsonObject prefJson = new JsonObject();
        for (Map.Entry<UUID, DisplayPref> entry : prefs.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("sidebar", entry.getValue().sidebar);
            o.addProperty("bossbar", entry.getValue().bossbar);
            o.addProperty("tab", entry.getValue().tab);
            prefJson.add(entry.getKey().toString(), o);
        }
        root.add("prefs", prefJson);

        JsonArray importedJson = new JsonArray();
        imported.forEach(id -> importedJson.add(id.toString()));
        root.add("imported", importedJson);
        return GSON.toJson(root);
    }

    public static BankBooks fromJson(String json, BankConfig fallback) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, CurrencyDef> defs = new LinkedHashMap<>();
        if (root.has("currencies")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("currencies").entrySet()) {
                defs.put(entry.getKey(), currencyFromJson(entry.getKey(), entry.getValue().getAsJsonObject()));
            }
        }
        if (defs.isEmpty()) {
            defs.putAll(fallback.asMap());
        }
        for (CurrencyDef def : fallback.currencies()) {
            defs.putIfAbsent(def.id(), def);
        }
        BankBooks books = new BankBooks(defs);
        books.numeraire = root.has("numeraire") ? root.get("numeraire").getAsString() : fallback.numeraire();
        books.feeFraction = new BigDecimal(root.get("fee").getAsString());
        books.frozen = root.has("frozen") && root.get("frozen").getAsBoolean();
        books.clampMin = optionalDecimal(root, "clampMin");
        books.clampMax = optionalDecimal(root, "clampMax");
        books.globalSidebar = bool(root, "globalSidebar", true);
        books.globalBossbar = bool(root, "globalBossbar", true);
        books.globalTab = bool(root, "globalTab", true);
        books.globalHologram = bool(root, "globalHologram", true);
        books.joinMessage = bool(root, "joinMessage", true);
        books.personalNoteCoverage = root.has("personalNoteCoverage")
                ? new BigDecimal(root.get("personalNoteCoverage").getAsString()) : BigDecimal.ONE;
        books.clock = root.has("clock") ? root.get("clock").getAsLong() : 0L;
        if (root.has("alphas")) {
            root.getAsJsonObject("alphas").entrySet()
                    .forEach(e -> books.alphas.put(e.getKey(), new BigDecimal(e.getValue().getAsString())));
        }
        for (CurrencyDef def : defs.values()) {
            books.alphas.putIfAbsent(def.id(), def.alphaPermille());
        }
        if (root.has("supplies")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("supplies").entrySet()) {
                JsonObject o = entry.getValue().getAsJsonObject();
                BigInteger reserve = new BigInteger(o.get("reserve").getAsString());
                BigInteger liability = new BigInteger(o.get("liability").getAsString());
                BigInteger anchor = new BigInteger(o.get("anchor").getAsString());
                BigInteger locked = new BigInteger(o.get("locked").getAsString());
                books.supplies.put(entry.getKey(), new Supply(entry.getKey(), reserve, liability, anchor, locked));
                books.currencies.putIfAbsent(entry.getKey(), CurrencyDef.orphan(entry.getKey()));
                books.alphas.putIfAbsent(entry.getKey(), new BigDecimal("1000"));
            }
        }
        for (CurrencyDef def : defs.values()) {
            books.supplies.putIfAbsent(def.id(), new Supply(
                    def.id(), def.initialReserve(), def.initialLiability(), def.initialLiability(), BigInteger.ZERO));
        }
        if (root.has("accounts")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("accounts").entrySet()) {
                UUID id = UUID.fromString(entry.getKey());
                Map<String, BigInteger> bals = new LinkedHashMap<>();
                entry.getValue().getAsJsonObject().entrySet()
                        .forEach(e -> bals.put(e.getKey(), new BigInteger(e.getValue().getAsString())));
                books.accounts.put(id, bals);
            }
        }
        if (root.has("personalReserves")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("personalReserves").entrySet()) {
                UUID id = UUID.fromString(entry.getKey());
                Map<String, BigInteger> row = new LinkedHashMap<>();
                entry.getValue().getAsJsonObject().entrySet()
                        .forEach(e -> row.put(e.getKey(), new BigInteger(e.getValue().getAsString())));
                if (!row.isEmpty()) {
                    books.personalReserves.put(id, row);
                }
            }
        }
        if (root.has("personalNotes")) {
            for (JsonElement element : root.getAsJsonArray("personalNotes")) {
                JsonObject o = element.getAsJsonObject();
                PersonalNote note = new PersonalNote(
                        UUID.fromString(o.get("id").getAsString()),
                        UUID.fromString(o.get("issuer").getAsString()),
                        o.get("name").getAsString(),
                        o.get("copperPar").getAsBoolean(),
                        new BigInteger(o.get("par").getAsString()));
                note.setCirculation(new BigInteger(o.get("circulation").getAsString()));
                note.setBuys(new BigInteger(o.get("buys").getAsString()));
                note.setSells(new BigInteger(o.get("sells").getAsString()));
                note.setLocked(new BigInteger(o.get("locked").getAsString()));
                books.personalNotes.put(note.id(), note);
            }
        }
        if (root.has("personalHoldings")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("personalHoldings").entrySet()) {
                UUID noteId = UUID.fromString(entry.getKey());
                Map<UUID, BigInteger> row = new LinkedHashMap<>();
                entry.getValue().getAsJsonObject().entrySet()
                        .forEach(e -> row.put(UUID.fromString(e.getKey()), new BigInteger(e.getValue().getAsString())));
                if (!row.isEmpty()) {
                    books.personalHoldings.put(noteId, row);
                }
            }
        }
        if (root.has("notePaper")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("notePaper").entrySet()) {
                BigInteger count = new BigInteger(entry.getValue().getAsString());
                if (count.signum() > 0) {
                    books.notePaper.put(entry.getKey(), count);
                }
            }
        }
        if (root.has("checks")) {
            for (JsonElement element : root.getAsJsonArray("checks")) {
                JsonObject o = element.getAsJsonObject();
                books.checks.restore(new CheckRecord(
                        UUID.fromString(o.get("id").getAsString()),
                        o.get("currency").getAsString(),
                        new BigInteger(o.get("amount").getAsString()),
                        UUID.fromString(o.get("issuer").getAsString()),
                        o.get("issuerName").getAsString(),
                        nullableUuid(o, "payee"),
                        o.get("issuedAt").getAsLong(),
                        CheckStatus.valueOf(o.get("status").getAsString()),
                        nullableUuid(o, "redeemedBy"),
                        o.has("redeemedAt") ? o.get("redeemedAt").getAsLong() : 0L));
            }
        }
        if (root.has("outlets")) {
            for (JsonElement element : root.getAsJsonArray("outlets")) {
                JsonObject o = element.getAsJsonObject();
                books.outlets.add(new OutletPoint(
                        o.get("name").getAsString(),
                        o.get("world").getAsString(),
                        o.get("x").getAsInt(),
                        o.get("y").getAsInt(),
                        o.get("z").getAsInt(),
                        o.get("radius").getAsDouble(),
                        o.has("lift") ? o.get("lift").getAsDouble() : 2.4,
                        !o.has("hologram") || o.get("hologram").getAsBoolean(),
                        nullableUuid(o, "hologramId"),
                        nullableUuid(o, "markerId")));
            }
        }
        if (root.has("prefs")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("prefs").entrySet()) {
                JsonObject o = entry.getValue().getAsJsonObject();
                DisplayPref pref = new DisplayPref();
                pref.sidebar = bool(o, "sidebar", true);
                pref.bossbar = bool(o, "bossbar", true);
                pref.tab = bool(o, "tab", true);
                books.prefs.put(UUID.fromString(entry.getKey()), pref);
            }
        }
        if (root.has("imported")) {
            for (JsonElement element : root.getAsJsonArray("imported")) {
                books.imported.add(UUID.fromString(element.getAsString()));
            }
        }
        return books;
    }

    private static JsonObject currencyJson(CurrencyDef def) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", def.kind().name());
        o.addProperty("item", def.itemId());
        o.addProperty("name", def.name());
        o.addProperty("nameEn", def.nameEn());
        o.addProperty("color", def.color());
        o.addProperty("issuable", def.issuable());
        o.add("backing", def.backing() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(def.backing()));
        o.add("parPerBacking", def.parPerBacking() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(def.parPerBacking().toPlainString()));
        o.add("haircut", def.haircut() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(def.haircut().toPlainString()));
        o.add("minReserveRatio", def.minReserveRatio() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(def.minReserveRatio().toPlainString()));
        o.addProperty("alphaPermille", def.alphaPermille().toPlainString());
        return o;
    }

    private static CurrencyDef currencyFromJson(String id, JsonObject o) {
        CurrencyKind kind = CurrencyKind.valueOf(o.get("kind").getAsString());
        BigDecimal alpha = new BigDecimal(o.get("alphaPermille").getAsString());
        return new CurrencyDef(
                id,
                kind,
                o.has("item") && !o.get("item").isJsonNull() ? o.get("item").getAsString() : "minecraft:paper",
                o.get("name").getAsString(),
                o.get("nameEn").getAsString(),
                o.get("color").getAsString(),
                BigInteger.ZERO,
                BigInteger.ZERO,
                alpha,
                o.has("issuable") && o.get("issuable").getAsBoolean(),
                nullableString(o, "backing"),
                nullableDecimal(o, "parPerBacking"),
                nullableDecimal(o, "haircut"),
                nullableDecimal(o, "minReserveRatio"));
    }

    private static UUID nullableUuid(JsonObject o, String name) {
        String raw = nullableString(o, name);
        return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
    }

    private static String nullableString(JsonObject o, String name) {
        if (!o.has(name) || o.get(name).isJsonNull()) {
            return null;
        }
        return o.get(name).getAsString();
    }

    private static BigDecimal nullableDecimal(JsonObject o, String name) {
        String raw = nullableString(o, name);
        return raw == null ? null : new BigDecimal(raw);
    }

    private static BigDecimal optionalDecimal(JsonObject root, String name) {
        if (!root.has(name) || root.get(name).isJsonNull()) {
            return null;
        }
        return new BigDecimal(root.get(name).getAsString());
    }

    private static boolean bool(JsonObject o, String name, boolean fallback) {
        return o.has(name) ? o.get(name).getAsBoolean() : fallback;
    }

    private BigInteger claimsOf(String currency) {
        return claimMap().getOrDefault(currency, BigInteger.ZERO);
    }

    private Map<String, BigInteger> claimMap() {
        Map<String, BigInteger> claims = new LinkedHashMap<>();
        for (Map<String, BigInteger> bals : accounts.values()) {
            bals.forEach((c, v) -> claims.merge(c, v, BigInteger::add));
        }
        for (CheckRecord record : checks.all()) {
            if (record.status() == CheckStatus.OUTSTANDING) {
                claims.merge(record.currency(), record.amount(), BigInteger::add);
            }
        }
        for (Map.Entry<String, BigInteger> entry : notePaper.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("c/")) {
                continue;
            }
            int slash = key.indexOf('/', 2);
            if (slash < 0) {
                continue;
            }
            String currency = key.substring(2, slash);
            BigInteger face = new BigInteger(key.substring(slash + 1));
            claims.merge(currency, face.multiply(entry.getValue()), BigInteger::add);
        }
        return claims;
    }

    private BigInteger holdingTotal(UUID noteId) {
        Map<UUID, BigInteger> row = personalHoldings.get(noteId);
        if (row == null) {
            return BigInteger.ZERO;
        }
        BigInteger sum = BigInteger.ZERO;
        for (BigInteger value : row.values()) {
            sum = sum.add(value);
        }
        return sum;
    }

    private BigInteger personalPaperValue(UUID noteId) {
        String prefix = "p/" + noteId + "/";
        BigInteger sum = BigInteger.ZERO;
        for (Map.Entry<String, BigInteger> entry : notePaper.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            BigInteger face = new BigInteger(entry.getKey().substring(prefix.length()));
            sum = sum.add(face.multiply(entry.getValue()));
        }
        return sum;
    }

    private void creditBalance(UUID player, String currency, BigInteger amount) {
        accounts.computeIfAbsent(player, id -> new LinkedHashMap<>())
                .merge(currency, amount, BigInteger::add);
    }

    private void debitBalance(UUID player, String currency, BigInteger amount) {
        Map<String, BigInteger> map = accounts.computeIfAbsent(player, id -> new LinkedHashMap<>());
        BigInteger next = map.getOrDefault(currency, BigInteger.ZERO).subtract(amount);
        if (next.signum() < 0) {
            throw new IllegalStateException("balance underflow");
        }
        if (next.signum() == 0) {
            map.remove(currency);
        } else {
            map.put(currency, next);
        }
    }

    private CurrencyDef need(String id) {
        return currencies.get(id);
    }

    private DisplayPref pref(UUID player) {
        return prefs.computeIfAbsent(player, id -> new DisplayPref());
    }

    private static final class DisplayPref {
        private boolean sidebar = true;
        private boolean bossbar = true;
        private boolean tab = true;
    }
}
