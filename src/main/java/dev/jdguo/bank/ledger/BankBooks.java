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
import dev.jdguo.bank.market.DebtSeizure;
import dev.jdguo.bank.market.NoteRegime;
import dev.jdguo.bank.market.NoteTrade;
import dev.jdguo.bank.market.PersonalMarket;
import dev.jdguo.bank.market.PersonalNote;
import dev.jdguo.bank.market.RegimePreview;
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
import java.util.function.Supplier;

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
    /** Central-owned savings. Never migrated from customer custody; old saves start at zero. */
    private final Map<String, BigDecimal> treasury = new LinkedHashMap<>();
    private final JsonArray systemFundingLog = new JsonArray();
    private final Map<String, Supply> supplies = new LinkedHashMap<>();
    private final Map<UUID, Map<String, BigDecimal>> accounts = new LinkedHashMap<>();
    /** Per-player reserves for a later personal mint. Not savings, and not the central vault. */
    private final Map<UUID, Map<String, BigInteger>> personalReserves = new LinkedHashMap<>();
    private final Map<String, BigDecimal> landGrantFraction = new LinkedHashMap<>();
    private final Map<UUID, BigDecimal> reserveDust = new LinkedHashMap<>();
    private final Map<UUID, BigDecimal> recoveredNotes = new LinkedHashMap<>();
    private final java.util.Set<UUID> reserveSwitching = new java.util.HashSet<>();
    private final Map<UUID, PersonalPolicy> policies = new LinkedHashMap<>();
    private final Map<String, Map<String, BigDecimal>> settledAssets = new LinkedHashMap<>();
    private final Map<String, JsonObject> clearingReceipts = new LinkedHashMap<>();

    private final Map<UUID, PersonalNote> personalNotes = new LinkedHashMap<>();
    /** Commodity units a bankrupt issuer still owes the central bank. */
    private final Map<UUID, Map<String, BigInteger>> issuerDebt = new LinkedHashMap<>();
    private final Map<UUID, Map<UUID, BigInteger>> personalOffers = new LinkedHashMap<>();
    private final Map<UUID, Map<UUID, BigInteger>> personalHoldings = new LinkedHashMap<>();
    private final CheckBook checks = new CheckBook();
    /** Sheets of stackable paper still out. Keyed by series, not by individual item. */
    private final Map<String, BigInteger> notePaper = new LinkedHashMap<>();
    private final List<OutletPoint> outlets = new ArrayList<>();
    private final Map<UUID, DisplayPref> prefs = new LinkedHashMap<>();
    private final Map<String, BigDecimal> alphas = new LinkedHashMap<>();
    private final java.util.Set<UUID> imported = new java.util.LinkedHashSet<>();

    private final Map<String, String> marketReceipts = new LinkedHashMap<>();
    private long checkpoint;

    public long checkpoint() {
        return checkpoint;
    }

    public void advanceCheckpoint() {
        checkpoint = Math.incrementExact(checkpoint);
    }

    public boolean marketReceipt(String reference) {
        return marketReceipts.containsKey(reference);
    }

    public TxResult postMarketMovement(String reference, String fingerprint, BigInteger amount,
                                       Supplier<TxResult> movement) {
        String prior = marketReceipts.get(reference);
        if (prior != null) {
            return prior.equals(fingerprint) ? TxResult.ok(amount, "")
                    : TxResult.fail("reference_conflict", "");
        }
        TxResult result = movement.get();
        if (result.success()) {
            marketReceipts.put(reference, fingerprint);
        }
        return result;
    }

    private String numeraire;
    private BigDecimal feeFraction;
    /** Annual rate on central-note M1. 0.02 is 2% over 365 game days. */
    private BigDecimal interestFraction = new BigDecimal("0.02");
    /** Annual rate on each personal note's circulation. Default is zero. */
    private BigDecimal personalInterestFraction = BigDecimal.ZERO;
    /** Commodity 联系汇率制 locks this share of par. It is not a second central-note reserve. */
    private static final BigDecimal PEG_COMMODITY_COVERAGE = new BigDecimal("0.80");
    /**
     * Retained for older saves and the ratio command. Issuance, switching, and redemption no longer
     * read them, except that a 联系汇率制 note anchored to 央行券 always covers 100% of par.
     */
    private BigDecimal floatCentralRatio = BigDecimal.ZERO;
    private BigDecimal pegCentralRatio = BigDecimal.ZERO;
    private BigDecimal hardCentralRatio = BigDecimal.ZERO;
    /** Central 央行券 posted by an issuer. Not savings, not interest-bearing, not issuance reserves. */
    private final Map<UUID, BigInteger> centralReserveAccounts = new LinkedHashMap<>();
    /**
     * Non-asset fee-funded interest budget. Actual fee assets belong to treasury.
     * Not an additional asset pool or player custody; interest is capped by available treasury.
     * Persisted as {@code interestPool} so older worlds keep the same balances.
     */
    private final Map<String, BigDecimal> interestPool = new LinkedHashMap<>();
    /** Sub-note remainder carried until it becomes a whole bank note. */
    private final Map<UUID, BigDecimal> interestDust = new LinkedHashMap<>();
    /**
     * 商店经营账户. One sub-ledger of the note savings pool, beside personal demand deposits.
     * Absent from older snapshots, which therefore keep a zero balance here.
     */
    private final Map<String, BigInteger> shopOperating = new LinkedHashMap<>();
    /**
     * 土地出让账户. Another sub-ledger of the same savings pool, not a third pool.
     * Keyed by currency. Older snapshots omit it and load as zero.
     */
    private final Map<String, BigInteger> landGrant = new LinkedHashMap<>();
    /** Fractional note carried for the shop operating account until it becomes a whole unit. */
    private final Map<String, BigDecimal> shopInterestDust = new LinkedHashMap<>();
    /** Fractional note carried for the land-grant account until it becomes a whole unit. */
    private final Map<String, BigDecimal> landInterestDust = new LinkedHashMap<>();
    /** Per personal-note series, then per holder: the fractional note carried forward. */
    private final Map<UUID, Map<UUID, BigDecimal>> personalInterestDust = new LinkedHashMap<>();
    /** World day last settled. -1 until the first tick anchors it, so old worlds do not pay retroactively. */
    private long lastInterestDay = -1L;
    private BigDecimal clampMin;
    private BigDecimal clampMax;
    private boolean frozen;
    private boolean globalSidebar = true;
    private boolean globalBossbar = true;
    private boolean globalTab = true;
    private boolean globalHologram = true;
    /** Set while reading a snapshot that still mixes savings and issuance in one reserve. */
    private boolean migrateVaults;
    private boolean joinMessage = true;
    private BigDecimal personalNoteCoverage = BigDecimal.ONE;
    /** Live copper ingots for one diamond. Absent until the opening books can quote it. */
    private BigDecimal liveCopperPerDiamond;
    /** Live central notes for one diamond. Absent when the official quote has no inverse. */
    private BigDecimal liveNotesPerDiamond;
    /** Official diamonds for one 央行券. Null when no notes are outstanding. */
    private BigDecimal officialDiamondsPerNote;
    /** Shop's book balance of a personal note, keyed by the note id. */
    private final Map<UUID, BigDecimal> shopPersonal = new LinkedHashMap<>();
    /** Fractional personal-note holding above the whole-note map. */
    private final Map<UUID, Map<UUID, BigDecimal>> holdingFraction = new LinkedHashMap<>();
    /** Fractional shop-operating balance above the whole-note map. */
    private final Map<String, BigDecimal> shopFraction = new LinkedHashMap<>();
    private long clock;

    private BankBooks(Map<String, CurrencyDef> currencies) {
        this.currencies = currencies;
    }

    public static BankBooks create(BankConfig config) {
        config.validate();
        BankBooks books = new BankBooks(new LinkedHashMap<>(config.asMap()));
        books.numeraire = config.numeraire();
        books.feeFraction = config.feeFraction();
        books.interestFraction = config.interestFraction();
        books.personalInterestFraction = config.personalInterestFraction();
        books.floatCentralRatio = config.floatCentralReserve();
        books.pegCentralRatio = config.pegCentralReserve();
        books.hardCentralRatio = config.hardCentralReserve();
        books.clampMin = config.clampMin();
        books.clampMax = config.clampMax();
        books.globalSidebar = config.sidebar();
        books.globalBossbar = config.bossbar();
        books.globalTab = config.tab();
        books.globalHologram = config.hologram();
        books.joinMessage = config.joinMessage();
        for (CurrencyDef def : config.currencies()) {
            books.alphas.put(def.id(), def.alphaPermille());
            books.supplies.put(def.id(), openingSupply(def));
        }
        books.fillMissingQuotes();
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
        addSavings(currency, amount);
        recomputeOfficialNoteQuote();
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
        TxResult capped = payable(player, currency, amount, partial);
        if (!capped.success()) {
            return capped;
        }
        BigInteger give = capped.amount();
        debitBalance(player, currency, give);
        addSavings(currency, give.negate());
        recomputeOfficialNoteQuote();
        return TxResult.ok(give, "Withdrew " + label(def) + " " + CompactFormat.format(give));
    }

    /**
     * A short balance or a short vault refuses the whole withdrawal. {@code partial} only caps a
     * request the balance already covers, at {@link #MAX_PHYSICAL}, so a max withdrawal of a large
     * integral balance can still leave the counter.
     */
    private TxResult payable(UUID player, String currency, BigInteger amount, boolean partial) {
        if (balanceExact(player, currency).compareTo(Money.of(amount)) < 0) {
            return TxResult.fail("insufficient_funds", "Not enough balance");
        }
        BigInteger give = amount;
        if (give.compareTo(MAX_PHYSICAL) > 0) {
            if (!partial) {
                return TxResult.fail("too_many_items", "You can withdraw at most " + MAX_PHYSICAL + " at once. Issue a bill for more.");
            }
            give = MAX_PHYSICAL;
        }
        if (supply(currency).depositVaultExact().compareTo(Money.of(give)) < 0) {
            return TxResult.fail("insufficient_reserves", "The vault doesn't have enough to pay this out");
        }
        return TxResult.ok(give, "ok");
    }

    /**
     * Savings withdrawals pay only from the deposit vault. They do not draw issuance reserves
     * and do not make up a shortfall with issuance copper.
     */
    private TxResult withdrawDiamond(UUID player, BigInteger amount, boolean partial) {
        TxResult capped = payable(player, "diamond", amount, partial);
        if (!capped.success()) {
            return capped;
        }
        BigInteger diamonds = capped.amount();
        debitBalance(player, "diamond", diamonds);
        addSavings("diamond", diamonds.negate());
        recomputeOfficialNoteQuote();
        return TxResult.ok(diamonds, "Withdrew diamonds " + CompactFormat.format(diamonds));
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
        if (note.bankrupt() || reserveSwitching.contains(note.id()) || noteHasPending(note.id())) {
            return PaperTake.fail("bankrupt", "Bankrupt notes cannot be withdrawn");
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
        } else if (personalNotes.get(presented.personalNote()).bankrupt()) {
            return TxResult.fail("bankrupt", "Bankrupt paper is a void record");
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
     * Cancels central notes, moves the priced metal from issuance reserves into the deposit vault,
     * and only then credits the player's savings. A short note balance or a short reserve refuses
     * the whole redemption. Other depositors are not the source of the metal.
     */
    private TxResult cashNotes(UUID player, BigInteger notes) {
        BigDecimal diamondsEach = unitsPer("diamond", "note");
        if (diamondsEach == null || diamondsEach.signum() <= 0) {
            return TxResult.fail("no_market", "No live quote");
        }
        BigDecimal diamonds = Money.of(diamondsEach.multiply(new BigDecimal(notes)));
        if (diamonds.signum() <= 0) {
            return TxResult.fail("bad_amount", "Quote does not cover a payout");
        }
        if (supply("diamond").freeReserveExact().compareTo(diamonds) < 0) {
            return TxResult.fail("insufficient_reserves", "Issuance reserves cannot cover this redemption");
        }
        FractionChange change = quoteFractionChange("diamond", diamonds);
        CurrencyDef note = primaryVirtual();
        if (note != null) {
            supply(note.id()).addLiability(Money.of(notes).negate());
        }
        supply("diamond").addReserve(diamonds.negate());
        addSavings("diamond", diamonds);
        creditBalance(player, "diamond", diamonds);
        String changeMessage = settleFractionChange(player, change);
        recomputeOfficialNoteQuote();
        return TxResult.ok(Money.units(diamonds), "Redeemed notes" + changeMessage);
    }

    private TxResult cancelNotesForMetal(UUID player, String metal, BigInteger metalQty) {
        CurrencyDef note = primaryVirtual();
        if (note == null) {
            return TxResult.fail("unknown_currency", "No central note");
        }
        BigDecimal gross = settlementCost(note.id(), metal, metalQty);
        BigDecimal principal = quoteCost(note.id(), metal, metalQty);
        if (gross == null || principal == null) {
            return TxResult.fail("no_market", "No live quote");
        }
        if (principal.compareTo(gross) > 0) {
            principal = gross;
        }
        BigDecimal fee = Money.of(gross.subtract(principal));
        if (balanceExact(player, note.id()).compareTo(gross) < 0) {
            return TxResult.fail("insufficient_funds", "Not enough balance");
        }
        if (supply(metal).freeReserveExact().compareTo(Money.of(metalQty)) < 0) {
            return TxResult.fail("insufficient_reserves", "Issuance reserves cannot cover this redemption");
        }
        debitBalance(player, note.id(), gross);
        supply(note.id()).addLiability(gross.negate());
        if (fee.signum() > 0) {
            addPool(note.id(), fee);
        }
        supply(metal).addReserve(Money.of(metalQty).negate());
        addSavings(metal, metalQty);
        creditBalance(player, metal, metalQty);
        recomputeOfficialNoteQuote();
        return TxResult.ok(metalQty, "Redeemed notes for " + CompactFormat.format(metalQty) + " " + metal);
    }

    /**
     * Sells already-issued 央行券 from the central-owned treasury. Liability stays put.
     * The metal paid is booked into the deposit vault, not into issuance reserves.
     */
    private TxResult buyIssuedNotes(UUID player, String metal, BigInteger notes) {
        CurrencyDef note = primaryVirtual();
        if (note == null) {
            return TxResult.fail("unknown_currency", "No central note");
        }
        if (!"copper".equals(metal) && !"diamond".equals(metal)) {
            return TxResult.fail("no_market", "No live quote");
        }
        BigDecimal price = settlementCost(metal, note.id(), notes);
        if (price == null) {
            return TxResult.fail("no_quote", "No live quote");
        }
        if (balanceExact(player, metal).compareTo(price) < 0) {
            return TxResult.fail("insufficient_funds", "Not enough balance");
        }
        BigDecimal unallocated = treasuryExact(note.id());
        if (unallocated.compareTo(Money.of(notes)) < 0) {
            return TxResult.fail("no_unallocated", "No unallocated notes");
        }
        if (price.stripTrailingZeros().scale() > 0) {
            return TxResult.fail("whole_physical_required", "实物不能拆分，请按整件实物数量预览兑换。");
        }
        if (supply(metal).depositVaultExact().compareTo(price) < 0)
            return TxResult.fail("insufficient_reserves", "实物保管库存不足，未执行兑换。");
        BigDecimal liability = supply(note.id()).liabilityExact();
        BigDecimal reserve = supply(metal).reserveExact();
        debitBalance(player, metal, price);
        collectCustodyIntoTreasury(metal, price);
        treasury.put(note.id(), Money.of(treasuryExact(note.id()).subtract(new BigDecimal(notes))));
        creditBalance(player, note.id(), notes);
        if (supply(note.id()).liabilityExact().compareTo(liability) != 0
                || supply(metal).reserveExact().compareTo(reserve) != 0) {
            throw new IllegalStateException("issued-note sale moved liability or issuance reserves");
        }
        recomputeOfficialNoteQuote();
        return TxResult.ok(Money.units(price), "Bought issued notes");
    }

    /** Physical/central-note exchange: quantity is physical on either side, never paper face value. */
    public HardExchangeQuote quoteHardExchange(String payId, String receiveId, BigInteger quantity) {
        if (quantity == null || quantity.signum() <= 0 || quantity.compareTo(MAX_PHYSICAL) > 0) return null;
        CurrencyDef pay = need(payId), receive = need(receiveId);
        if (pay == null || receive == null || payId.equals(receiveId)) return null;
        boolean selling = pay.kind() == CurrencyKind.COMMODITY && centralNote(receive);
        boolean buying = centralNote(pay) && receive.kind() == CurrencyKind.COMMODITY;
        if (!selling && !buying) return null;
        BigDecimal rate = selling ? unitsPer(receiveId, payId) : unitsPer(payId, receiveId);
        if (rate == null || rate.signum() <= 0) return null;
        BigDecimal physical = Money.of(quantity);
        BigDecimal notes = selling
                ? rate.multiply(physical).divide(BigDecimal.ONE.add(feeFraction), Money.SCALE, RoundingMode.DOWN)
                : rate.multiply(physical).multiply(BigDecimal.ONE.add(feeFraction)).setScale(Money.SCALE, RoundingMode.UP);
        if (notes.signum() <= 0) return null;
        return new HardExchangeQuote(payId, receiveId, quantity, selling ? physical : notes,
                selling ? notes : physical, rate, feeFraction);
    }

    /** Recompute the exact quotation before any mutation; old quotes never execute at a new price. */
    public TxResult confirmHardExchange(UUID player, HardExchangeQuote quote) {
        if (quote == null || !quote.equals(quoteHardExchange(quote.pay(), quote.receive(), quote.quantity())))
            return TxResult.fail("quote_changed", "牌价或手续费已改变，请重新预览。");
        boolean selling = need(quote.pay()).kind() == CurrencyKind.COMMODITY;
        if (!selling && frozen) return TxResult.fail("frozen", "央行券兑付已暂停。");
        if (balanceExact(player, quote.pay()).compareTo(quote.debit()) < 0)
            return TxResult.fail("insufficient_funds", "账户余额不足，未执行兑换。");
        if (selling) {
            if (supply(quote.pay()).depositVaultExact().compareTo(quote.debit()) < 0)
                return TxResult.fail("insufficient_reserves", "实物保管库存不足，未执行兑换。");
            BigDecimal available = treasuryExact(quote.receive());
            if (available.compareTo(quote.credit()) < 0)
                return TxResult.fail("no_unallocated", "已发行的可售央行券不足，未执行兑换。");
            // The payer relinquishes its claim; the actual metal becomes central-owned, not new stock.
            debitBalance(player, quote.pay(), quote.debit());
            collectCustodyIntoTreasury(quote.pay(), quote.debit());
            treasury.put(quote.receive(), Money.of(available.subtract(quote.credit())));
            creditBalance(player, quote.receive(), quote.credit());
        } else {
            if (supply(quote.receive()).freeReserveExact().compareTo(quote.credit()) < 0)
                return TxResult.fail("insufficient_reserves", "可兑付实物准备金不足，未执行兑换。");
            BigDecimal principal = quote.rate().multiply(quote.credit()).setScale(Money.SCALE, RoundingMode.DOWN);
            BigDecimal fee = quote.debit().subtract(principal);
            debitBalance(player, quote.pay(), quote.debit());
            supply(quote.pay()).addLiability(quote.debit().negate());
            if (fee.signum() > 0) addPool(quote.pay(), fee);
            supply(quote.receive()).addReserve(quote.credit().negate());
            addSavings(quote.receive(), quote.credit());
            creditBalance(player, quote.receive(), quote.credit());
        }
        recomputeOfficialNoteQuote();
        return TxResult.ok(quote.quantity(), "兑换完成：" + hardExchangeDescription(quote) + "（已入个人储蓄）。");

    }

    /** Both preview and receipt retain the reference rate, spread and exact amounts. */
    public String hardExchangeDescription(HardExchangeQuote quote) {
        boolean selling = need(quote.pay()).kind() == CurrencyKind.COMMODITY;
        String metal = label(need(selling ? quote.pay() : quote.receive()));
        String notes = label(need(selling ? quote.receive() : quote.pay()));
        return "参考价：1 " + metal + " = " + quote.rate().stripTrailingZeros().toPlainString() + " " + notes
                + "；兑换差价 " + quote.feeFraction().multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString()
                + "%；实际付出 " + Money.stored(quote.debit()) + " " + label(need(quote.pay()))
                + "，实收 " + Money.stored(quote.credit()) + " " + label(need(quote.receive()));
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
        if (pay.kind() == CurrencyKind.COMMODITY && recv.kind() == CurrencyKind.VIRTUAL) {
            if (centralNote(recv)) {
                return buyIssuedNotes(player, payId, receiveQty);
            }
            return TxResult.fail("manual_issue", "Bank notes are issued only by an operator");
        }
        if (pay.kind() == CurrencyKind.VIRTUAL && recv.kind() == CurrencyKind.COMMODITY) {
            return cancelNotesForMetal(player, receiveId, receiveQty);
        }
        if (pay.kind() != CurrencyKind.COMMODITY || recv.kind() != CurrencyKind.COMMODITY) {
            return TxResult.fail("no_market", "No live quote");
        }
        BigDecimal gross = settlementCost(payId, receiveId, receiveQty);
        BigDecimal principal = quoteCost(payId, receiveId, receiveQty);
        if (gross == null || principal == null) {
            return TxResult.fail("no_market", "No live quote");
        }
        if (principal.compareTo(gross) > 0) {
            principal = gross;
        }
        if (gross.stripTrailingZeros().scale() > 0) {
            return TxResult.fail("whole_physical_required", "实物不能拆分，请使用央行券作为兑换中介。");
        }
        BigDecimal fee = Money.of(gross.subtract(principal));
        if (balanceExact(player, payId).compareTo(gross) < 0) {
            return TxResult.fail("insufficient_funds", "Not enough balance. Need " + label(pay) + " " + Money.stored(gross));
        }
        debitBalance(player, payId, gross);
        addSavings(payId, gross.negate());
        if (fee.signum() > 0) {
            addPool(payId, fee);
        }
        creditBalance(player, receiveId, receiveQty);
        addSavings(receiveId, receiveQty);
        recomputeOfficialNoteQuote();
        return TxResult.ok(Money.units(gross), BigInteger.ZERO, "Exchanged " + Money.stored(gross) + " " + label(pay)
                + " for " + CompactFormat.format(receiveQty) + " " + label(recv));
    }

    /** Four-decimal units of {@code payId} for {@code receiveQty} of {@code receiveId}, fee included. */
    public BigDecimal settlementCost(String payId, String receiveId, BigInteger receiveQty) {
        BigDecimal bare = quoteCost(payId, receiveId, receiveQty);
        if (bare == null) {
            return null;
        }
        return Money.of(bare.multiply(BigDecimal.ONE.add(feeFraction)));
    }

    private BigDecimal quoteCost(String payId, String receiveId, BigInteger receiveQty) {
        if (receiveQty == null || receiveQty.signum() <= 0) {
            return null;
        }
        BigDecimal q = unitsPer(payId, receiveId);
        if (q == null || q.signum() <= 0) {
            return null;
        }
        BigDecimal settled = Money.of(q.multiply(new BigDecimal(receiveQty)));
        return settled.signum() > 0 ? settled : null;
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
        CheckBook.RedeemCode code = checks.validate(player, presented);
        return switch (code) {
            case UNKNOWN -> TxResult.fail("invalid_check", "Unrecognized bill");
            case TAMPERED -> TxResult.fail("tampered", "The bill doesn't match the stub");
            case ALREADY -> TxResult.fail("already_redeemed", "This bill was already redeemed");
            case WRONG_PAYEE -> TxResult.fail("wrong_payee", "This bill isn't made out to you");
            case OK -> {
                if ("note".equals(presented.currency())) {
                    TxResult payout = cashNotes(player, presented.amount());
                    if (payout.success()) checks.redeem(player, presented, System.currentTimeMillis());
                    yield payout;
                }
                creditBalance(player, presented.currency(), presented.amount());
                checks.redeem(player, presented, System.currentTimeMillis());
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
        supply(currency).addLiability(amount);
        if (centralNote(def)) {
            treasury.put(currency, Money.of(treasuryExact(currency).add(new BigDecimal(amount))));
            recomputeOfficialNoteQuote();
        }
        return TxResult.ok(amount, "Issued unbacked " + label(def) + " " + CompactFormat.format(amount));
    }

    /** Issues a four-decimal unbacked balance. A non-positive amount after rounding is refused. */
    public TxResult creditUnbacked(UUID player, String currency, BigDecimal amount) {
        CurrencyDef def = need(currency);
        if (def == null) {
            return TxResult.fail("unknown_currency", "Unknown currency");
        }
        if (def.kind() != CurrencyKind.VIRTUAL || !def.issuable()) {
            return TxResult.fail("not_issuable", "This currency can't be issued as credit");
        }
        BigDecimal posted = amount == null ? Money.ZERO : Money.of(amount);
        if (posted.signum() <= 0) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        supply(currency).addLiability(posted);
        if (centralNote(def)) {
            treasury.put(currency, Money.of(treasuryExact(currency).add(posted)));
            recomputeOfficialNoteQuote();
        }
        return TxResult.ok(Money.units(posted), "Issued unbacked " + label(def) + " " + Money.stored(posted));
    }

    /**
     * Credits a player's own savings and the matching liability. Not an issuance action:
     * admin issue and retire never call this.
     */
    public TxResult creditHeld(UUID player, String currency, BigInteger amount) {
        CurrencyDef def = need(currency);
        if (player == null || def == null || def.kind() != CurrencyKind.VIRTUAL) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        if (amount == null || amount.signum() <= 0) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        creditBalance(player, currency, amount);
        supply(currency).addLiability(amount);
        if (centralNote(def)) {
            recomputeOfficialNoteQuote();
        }
        return TxResult.ok(amount, "Credited savings");
    }

    public TxResult creditHeld(UUID player, String currency, BigDecimal amount) {
        CurrencyDef def = need(currency);
        if (player == null || def == null || def.kind() != CurrencyKind.VIRTUAL) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        BigDecimal posted = amount == null ? Money.ZERO : Money.of(amount);
        if (posted.signum() <= 0) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        creditBalance(player, currency, posted);
        supply(currency).addLiability(posted);
        if (centralNote(def)) {
            recomputeOfficialNoteQuote();
        }
        return TxResult.ok(Money.units(posted), "Credited savings");
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
        TxResult pledged = lockIssuanceCollateral(def, lock);
        if (!pledged.success()) {
            return pledged;
        }
        supply(currency).addLiability(amount);
        supply(currency).addReserve(amount);
        if (centralNote(def)) {
            treasury.put(currency, Money.of(treasuryExact(currency).add(new BigDecimal(amount))));
            recomputeOfficialNoteQuote();
        }
        return TxResult.ok(amount, "Issued reserve-backed " + label(def) + " " + CompactFormat.format(amount)
                + ", locked " + CompactFormat.format(lock) + " " + label(need(def.backing())));
    }

    /** Target-rate quote uses the live gap formula, not the configured par as an exchange rate.
     * Existing collateral caps remain a second, conservative issuance safeguard. */
    public CentralIssuePlan previewCentralIssue(BigDecimal target) {
        if (target == null || target.signum() <= 0 || target.scale() > Money.SCALE) {
            return rejectedCentralIssue(target, "目标汇率必须为正数，最多四位小数");
        }
        CurrencyDef note = primaryVirtual();
        if (note == null) return rejectedCentralIssue(target, "未配置央行券");
        recomputeOfficialNoteQuote();
        BigDecimal reserve = issuanceDiamondEquivalent();
        BigDecimal alpha = alphaOf(note.id()).divide(new BigDecimal("1000"), 8, RoundingMode.HALF_UP);
        BigDecimal denominator = target.add(alpha);
        if (reserve.signum() <= 0 || denominator.signum() <= 0) {
            return rejectedCentralIssue(target, "没有可用于发行的实际准备金");
        }
        // target = ((1 + alpha) * reserves - alpha * liabilities) / liabilities.
        BigDecimal desired = reserve.multiply(BigDecimal.ONE.add(alpha)).divide(denominator, 8, RoundingMode.DOWN);
        BigInteger amount = desired.subtract(supply(note.id()).liabilityExact()).setScale(0, RoundingMode.DOWN).toBigInteger();
        if (amount.signum() <= 0) return rejectedCentralIssue(target, "该目标不能通过增发达到；请调整目标或使用回收");
        if (amount.compareTo(backedRoom(note)) > 0) {
            return rejectedCentralIssue(target, "现有可用准备金不足以安全发行该数量；不会动用玩家保管款");
        }
        BankBooks simulation = BankBooks.fromJson(toJson(), BankConfig.defaults());
        TxResult test = simulation.creditBacked(new UUID(0, 0), note.id(), amount);
        if (!test.success()) return rejectedCentralIssue(target, "准备金校验失败：" + test.code());
        BigDecimal result = simulation.unitsPer("diamond", note.id());
        if (result == null) return rejectedCentralIssue(target, "发行后无有效汇率，拒绝操作");
        return new CentralIssuePlan(true, "整张发行取整，实际汇率以预览为准", target, amount, result,
                reserve, simulation.supply(note.id()).liabilityExact(), centralPolicyFingerprint());
    }

    private CentralIssuePlan rejectedCentralIssue(BigDecimal target, String reason) {
        return new CentralIssuePlan(false, reason, target, BigInteger.ZERO, null, Money.ZERO, Money.ZERO, "");
    }

    private String centralPolicyFingerprint() {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Requotes inside the committing transaction; no stale quote can spend reserves. */
    public TxResult confirmCentralIssue(UUID administrator, CentralIssuePlan approved) {
        if (approved == null || !approved.allowed() || !centralPolicyFingerprint().equals(approved.fingerprint())) {
            return TxResult.fail("stale_preview", "账本已变化，请重新预览后确认");
        }
        CentralIssuePlan fresh = previewCentralIssue(approved.target());
        if (!fresh.allowed() || !fresh.amount().equals(approved.amount())) {
            return TxResult.fail("stale_preview", "可发行量已变化，请重新预览");
        }
        return creditBacked(administrator, primaryVirtual().id(), fresh.amount());
    }

    public TxResult retire(UUID player, String currency, BigInteger amount) {
        CurrencyDef def = need(currency);
        if (def == null) {
            return TxResult.fail("unknown_currency", "Unknown currency");
        }
        if (amount == null || amount.signum() <= 0) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        Supply self = supply(currency);
        BigDecimal unallocated = centralNote(def) ? treasuryExact(currency)
                : self.liabilityExact().subtract(publicM1Exact(currency)).subtract(treasuryExact(currency));
        if (unallocated.compareTo(Money.of(amount)) < 0) {
            return TxResult.fail("insufficient_issue", "央行自有央行券不足，不能回收玩家或系统账户中的货币");
        }
        BigInteger backedOutstanding = self.reserve().subtract(self.anchor());
        if (backedOutstanding.signum() < 0) {
            backedOutstanding = BigInteger.ZERO;
        }
        BigInteger unbacked = self.liability().subtract(backedOutstanding);
        if (unbacked.signum() < 0) {
            unbacked = BigInteger.ZERO;
        }
        if (centralNote(def)) treasury.put(currency, Money.of(treasuryExact(currency).subtract(new BigDecimal(amount))));
        self.addLiability(amount.negate());
        if (def.kind() == CurrencyKind.VIRTUAL && amount.compareTo(unbacked) > 0 && def.backing() != null) {
            BigInteger backedBurn = amount.subtract(unbacked);
            if (backedBurn.compareTo(self.reserve()) > 0) {
                backedBurn = self.reserve();
            }
            self.addReserve(backedBurn.negate());
            BigInteger unlock = CreditDesk.backingUnitsNeeded(backedBurn, def.parPerBacking(), def.haircut(), def.minReserveRatio());
            unlockIssuanceCollateral(def, unlock);
        }
        if (centralNote(def)) {
            recomputeOfficialNoteQuote();
        }
        return TxResult.ok(amount, "Retired " + label(def) + " " + CompactFormat.format(amount));
    }

    /**
     * Reserve-backed notes still issuable. The central 央行券 counts free copper, converted at the
     * live copper-per-diamond rate, plus free diamonds. Metal already locked is omitted, and notes
     * already issued against that lock are not subtracted a second time.
     */
    public BigInteger backedRoom(CurrencyDef def) {
        if (def.kind() != CurrencyKind.VIRTUAL || def.backing() == null) {
            return BigInteger.ZERO;
        }
        BigInteger stock = centralNote(def) ? diamondEquivalentFree() : supply(def.backing()).freeReserve();
        BigInteger max = CreditDesk.maxLiabilities(stock, def.parPerBacking(), def.haircut(), def.minReserveRatio());
        return CreditDesk.room(max, unbackedOutstanding(def));
    }

    /** Free diamonds plus free copper divided by the current copper-per-diamond rate. */
    public BigInteger diamondEquivalentFree() {
        BigInteger diamonds = supply("diamond").freeReserve();
        BigInteger rate = copperPerDiamond();
        if (rate == null || rate.signum() <= 0) {
            return diamonds;
        }
        return diamonds.add(supply("copper").freeReserve().divide(rate));
    }

    private boolean centralNote(CurrencyDef def) {
        CurrencyDef note = primaryVirtual();
        return note != null && def.id().equals(note.id());
    }

    /** Notes whose issuance did not lock commodity. The opening seed in the anchor is not one of them. */
    private BigInteger unbackedOutstanding(CurrencyDef def) {
        Supply self = supply(def.id());
        BigInteger backed = self.reserve().subtract(self.anchor());
        if (backed.signum() < 0) {
            backed = BigInteger.ZERO;
        }
        BigInteger unbacked = self.liability().subtract(backed);
        return unbacked.signum() < 0 ? BigInteger.ZERO : unbacked;
    }

    /**
     * Locks {@code diamondUnits} of diamond-equivalent collateral, diamonds first and then copper
     * at the current hard-currency rate. Nothing is locked when the free stock is short.
     */
    private TxResult lockIssuanceCollateral(CurrencyDef def, BigInteger diamondUnits) {
        if (diamondUnits.signum() <= 0) {
            return TxResult.ok(BigInteger.ZERO, "ok");
        }
        if (!centralNote(def)) {
            Supply vault = supply(def.backing());
            if (vault.freeReserve().compareTo(diamondUnits) < 0) {
                return TxResult.fail("insufficient_reserves", "Not enough free reserves to lock");
            }
            vault.addLocked(diamondUnits);
            return TxResult.ok(diamondUnits, "ok");
        }
        Supply diamonds = supply("diamond");
        BigInteger fromDiamond = diamondUnits.min(diamonds.freeReserve());
        BigInteger rest = diamondUnits.subtract(fromDiamond);
        BigInteger fromCopper = BigInteger.ZERO;
        BigInteger rate = copperPerDiamond();
        if (rest.signum() > 0) {
            if (rate == null || rate.signum() <= 0) {
                return TxResult.fail("insufficient_reserves", "Not enough free reserves to lock");
            }
            fromCopper = rest.multiply(rate);
            if (supply("copper").freeReserve().compareTo(fromCopper) < 0) {
                return TxResult.fail("insufficient_reserves", "Not enough free reserves to lock");
            }
        }
        if (fromDiamond.signum() > 0) {
            diamonds.addLocked(fromDiamond);
        }
        if (fromCopper.signum() > 0) {
            supply("copper").addLocked(fromCopper);
        }
        return TxResult.ok(diamondUnits, "ok");
    }

    private void unlockIssuanceCollateral(CurrencyDef def, BigInteger diamondUnits) {
        if (diamondUnits.signum() <= 0) {
            return;
        }
        if (!centralNote(def)) {
            Supply vault = supply(def.backing());
            BigInteger unlock = diamondUnits.min(vault.locked());
            vault.addLocked(unlock.negate());
            return;
        }
        Supply diamonds = supply("diamond");
        BigInteger fromDiamond = diamondUnits.min(diamonds.locked());
        diamonds.addLocked(fromDiamond.negate());
        BigInteger rest = diamondUnits.subtract(fromDiamond);
        if (rest.signum() <= 0) {
            return;
        }
        BigInteger rate = copperPerDiamond();
        if (rate == null || rate.signum() <= 0) {
            return;
        }
        Supply copper = supply("copper");
        BigInteger fromCopper = rest.multiply(rate).min(copper.locked());
        if (fromCopper.signum() > 0) {
            copper.addLocked(fromCopper.negate());
        }
    }

    public BigInteger cost(String payId, String receiveId, BigInteger receiveQty) {
        return FxMath.cost(receiveQty, abundance(payId), abundance(receiveId), feeFraction, clampMin, clampMax);
    }

    /**
     * Units of {@code pay} for one unit of {@code receive}, from the stored live quote.
     * Null when that quote was never posted. Nothing here recomputes a missing quote from par.
     */
    public BigDecimal unitsPer(String pay, String receive) {
        if (pay == null || receive == null || pay.equals(receive)) {
            return pay != null && pay.equals(receive) ? BigDecimal.ONE : null;
        }
        String noteId = centralNoteId();
        if ("copper".equals(pay) && "diamond".equals(receive)) {
            recomputeOfficialCopperQuote();
            return liveCopperPerDiamond;
        }
        if ("diamond".equals(pay) && "copper".equals(receive)) {
            recomputeOfficialCopperQuote();
            return inverse(liveCopperPerDiamond);
        }
        if (noteId.equals(pay) && "diamond".equals(receive)) {
            recomputeOfficialNoteQuote();
            return liveNotesPerDiamond;
        }
        if ("diamond".equals(pay) && noteId.equals(receive)) {
            recomputeOfficialNoteQuote();
            return officialDiamondsPerNote;
        }
        if (noteId.equals(pay) && "copper".equals(receive)) {
            recomputeOfficialNoteQuote();
            if (liveNotesPerDiamond == null || liveCopperPerDiamond == null || liveCopperPerDiamond.signum() == 0) {
                return null;
            }
            BigDecimal notesPerCopper = liveNotesPerDiamond.divide(liveCopperPerDiamond, Money.SCALE, RoundingMode.HALF_UP);
            return notesPerCopper.signum() > 0 ? notesPerCopper : null;
        }
        if ("copper".equals(pay) && noteId.equals(receive)) {
            recomputeOfficialNoteQuote();
            if (officialDiamondsPerNote == null || liveCopperPerDiamond == null || liveCopperPerDiamond.signum() <= 0) {
                return null;
            }
            if (officialDiamondsPerNote.signum() <= 0) {
                return Money.ZERO;
            }
            return Money.of(liveCopperPerDiamond.multiply(officialDiamondsPerNote));
        }
        return null;
    }

    private BigDecimal clampQuote(BigDecimal quote) {
        if (quote == null || quote.signum() <= 0) {
            return null;
        }
        if (clampMin != null && quote.compareTo(clampMin) < 0) {
            quote = clampMin;
        }
        if (clampMax != null && quote.compareTo(clampMax) > 0) {
            quote = clampMax;
        }
        return quote;
    }

    private static BigDecimal inverse(BigDecimal quote) {
        if (quote == null || quote.signum() <= 0) {
            return null;
        }
        return BigDecimal.ONE.divide(quote, FxMath.MC);
    }

    private String centralNoteId() {
        CurrencyDef note = primaryVirtual();
        return note == null || note.id() == null ? "note" : note.id();
    }

    /**
     * Recomputes both official quotes from the books. A stored quote is not an input.
     */
    private void fillMissingQuotes() {
        recomputeOfficialNoteQuote();
    }

    /**
     * Datapack gap, without an integer floor.
     * {@code stock + (stock - claims) * alpha / 1000}. Alpha 1000 is {@code 2 * stock - claims}.
     */
    private BigDecimal gapEffective(BigDecimal stock, BigDecimal claims, String currency) {
        BigDecimal alpha = alphaOf(currency);
        BigDecimal gap = stock.subtract(claims).multiply(alpha)
                .divide(new BigDecimal("1000"), 8, RoundingMode.HALF_UP);
        return stock.add(gap);
    }

    /**
     * Bank-registered physical holdings, counted once. Custody includes claims owned by
     * players, personal issuers and system accounts; those claims must not be added again.
     * Fees are already in treasury; the interest allocation budget is not an asset.
     * Locked reserve is part of reserve and is not counted again.
     * This is a valuation statistic, never permission to spend custody or locked metal.
     */
    public BigDecimal commodityHoldingsExact(String currency) {
        CurrencyDef def = need(currency);
        if (def == null || def.kind() != CurrencyKind.COMMODITY) return null;
        BigDecimal reserve = supply(currency).reserveExact();
        BigDecimal treasury = treasuryExact(currency);
        BigDecimal custody = supply(currency).depositVaultExact();
        if (reserve.signum() < 0 || treasury.signum() < 0 || custody.signum() < 0) return null;
        return Money.of(reserve.add(treasury).add(custody));
    }

    /** 1 diamond = registered copper holdings / registered diamond holdings. */
    private void recomputeOfficialCopperQuote() {
        BigDecimal copper = commodityHoldingsExact("copper");
        BigDecimal diamonds = commodityHoldingsExact("diamond");
        if (copper == null || diamonds == null || copper.signum() <= 0 || diamonds.signum() <= 0) {
            liveCopperPerDiamond = null;
            return;
        }
        BigDecimal rate = copper.divide(diamonds, Money.SCALE, RoundingMode.HALF_UP);
        // A zero from precision loss is not an executable zero-price asset.
        liveCopperPerDiamond = rate.signum() > 0 ? clampQuote(rate) : null;
    }

    /**
     * 「1 央行券 = xxx 钻石」, the same gap applied to diamond-equivalent issuance reserves and notes outstanding.
     * {@code L = 0} or a non-positive effective reserve clears the quote. Deposit vaults are not reserves.
     */
    private void recomputeOfficialNoteQuote() {
        recomputeOfficialCopperQuote();
        CurrencyDef note = primaryVirtual();
        if (note == null || supplies.get(note.id()) == null) {
            officialDiamondsPerNote = null;
            liveNotesPerDiamond = null;
            return;
        }
        BigDecimal liabilities = supply(note.id()).liabilityExact();
        if (liabilities.signum() <= 0) {
            officialDiamondsPerNote = null;
            liveNotesPerDiamond = null;
            return;
        }
        BigDecimal reserves = issuanceDiamondEquivalent();
        BigDecimal effective = gapEffective(reserves, liabilities, note.id());
        if (effective.signum() <= 0) {
            officialDiamondsPerNote = null;
            liveNotesPerDiamond = null;
            return;
        }
        BigDecimal quote = effective.divide(liabilities, Money.SCALE, RoundingMode.HALF_UP);
        if (quote.signum() <= 0) {
            officialDiamondsPerNote = null;
            liveNotesPerDiamond = null;
            return;
        }
        officialDiamondsPerNote = quote;
        BigDecimal inverse = BigDecimal.ONE.divide(quote, Money.SCALE, RoundingMode.HALF_UP);
        liveNotesPerDiamond = inverse.signum() > 0 ? inverse : null;
    }

    /** Diamond-equivalent issuance reserves. Locked metal stays inside. Deposit vaults do not. */
    private BigDecimal issuanceDiamondEquivalent() {
        BigDecimal diamonds = supplies.get("diamond") == null ? Money.ZERO : supply("diamond").reserveExact();
        BigDecimal copper = supplies.get("copper") == null ? Money.ZERO : supply("copper").reserveExact();
        if (liveCopperPerDiamond == null || liveCopperPerDiamond.signum() <= 0 || copper.signum() <= 0) {
            return diamonds;
        }
        return diamonds.add(copper.divide(liveCopperPerDiamond, 8, RoundingMode.HALF_UP));
    }


    public BigDecimal abundance(String id) {
        CurrencyDef def = need(id);
        if (def == null) {
            return null;
        }
        Supply self = supply(id);
        BigDecimal alpha = alphaOf(id);
        if (def.kind() == CurrencyKind.COMMODITY) {
            return FxMath.commodityAbundance(self.reserve(), self.reserve(), alpha);
        }
        // Public M1 sits in liability. The opening seed in anchor/reserve keeps the peg at par
        // before anyone holds a note, without counting that seed as money in circulation.
        BigDecimal scale = FxMath.virtualScale(self.reserve(), self.liability().add(self.anchor()), alpha);
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
        if (self == null) {
            return null;
        }
        BigInteger denom = self.liability().add(self.anchor());
        if (denom.signum() <= 0) {
            return null;
        }
        return new BigDecimal(self.reserve()).divide(new BigDecimal(denom), FxMath.MC);
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

    /** Redemption availability follows the same quote and free physical reserve checks as execution. */
    public String statusLabel() {
        if (frozen) return "央行券兑付已暂停";
        CurrencyDef note = primaryVirtual();
        if (note == null) return "未配置央行券";
        boolean quoted = false;
        for (CurrencyDef def : currencies.values()) {
            if (def.kind() != CurrencyKind.COMMODITY) continue;
            if (quoteHardExchange(note.id(), def.id(), BigInteger.ONE) == null) continue;
            quoted = true;
            if (supply(def.id()).freeReserveExact().compareTo(BigDecimal.ONE) >= 0)
                return "可兑付（以所选实物可用准备金为限）";
        }
        return quoted ? "可兑付实物准备金不足" : "暂无可用兑付牌价";
    }

    public String statusLabelEn() {
        if (frozen) return "redemption suspended";
        CurrencyDef note = primaryVirtual();
        if (note == null) return "central note not configured";
        boolean quoted = false;
        for (CurrencyDef def : currencies.values()) {
            if (def.kind() != CurrencyKind.COMMODITY || quoteHardExchange(note.id(), def.id(), BigInteger.ONE) == null) continue;
            quoted = true;
            if (supply(def.id()).freeReserveExact().compareTo(BigDecimal.ONE) >= 0) return "redemption available within physical reserves";
        }
        return quoted ? "insufficient free physical reserves" : "redemption quote unavailable";
    }

    public JsonArray redemptionDetails() {
        JsonArray rows = new JsonArray(); CurrencyDef note = primaryVirtual();
        for (CurrencyDef def : currencies.values()) {
            if (def.kind() != CurrencyKind.COMMODITY) continue;
            JsonObject row = new JsonObject();
            row.addProperty("id", def.id()); row.addProperty("name", label(def));
            row.addProperty("available", supply(def.id()).freeReserveExact().max(BigDecimal.ZERO).toBigInteger().toString());
            row.addProperty("locked", Money.stored(supply(def.id()).lockedExact()));
            HardExchangeQuote quote = note == null ? null : quoteHardExchange(note.id(), def.id(), BigInteger.ONE);
            row.addProperty("status", frozen ? "已暂停" : quote == null ? "暂无牌价"
                    : supply(def.id()).freeReserveExact().compareTo(BigDecimal.ONE) < 0 ? "不足 1 个" : "可兑付");
            if (quote != null) row.addProperty("costPerItem", Money.stored(quote.debit()));
            rows.add(row);
        }
        return rows;
    }

    private static String label(CurrencyDef def) {
        if (def == null) {
            return "?";
        }
        String en = def.name();
        if (en == null || en.isBlank()) {
            return def.id();
        }
        return en;
    }

    /** Changes display metadata only; all balances, instruments and references retain their IDs. */
    public TxResult renameCentralCurrency(String name, boolean authorized) {
        if (!authorized) return TxResult.fail("denied", "只有管理员可以更名央行券。");
        String clean = name == null ? "" : name.trim();
        if (!clean.matches("[\\p{L}\\p{N}·_-]{1,16}"))
            return TxResult.fail("bad_name", "名称须为 1 至 16 个文字、数字、间隔点、下划线或连字符。");
        CurrencyDef note = primaryVirtual();
        if (note == null) return TxResult.fail("none", "未设置央行券。");
        if (currencies.values().stream().anyMatch(c -> !c.id().equals(note.id()) && clean.equals(c.name()))
                || personalNotes.values().stream().anyMatch(c -> clean.equals(c.name())))
            return TxResult.fail("duplicate_name", "该名称已被其他货币使用。");
        currencies.put(note.id(), note.withName(clean));
        return TxResult.ok(BigInteger.ZERO, "央行券已更名为「" + clean + "」。");
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

    /** Whole units toward zero. An integral balance of {@code 64.0000} is {@code 64}. */
    public BigInteger balance(UUID player, String currency) {
        return Money.units(balanceExact(player, currency));
    }

    /** Book balance at four decimal places. */
    public BigDecimal balanceExact(UUID player, String currency) {
        Map<String, BigDecimal> map = accounts.get(player);
        if (map == null) {
            return Money.ZERO;
        }
        return map.getOrDefault(currency, Money.ZERO);
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
    public BigDecimal interestFraction() { return interestFraction; }

    public void setInterestFraction(BigDecimal interestFraction) {
        if (interestFraction == null || interestFraction.signum() < 0 || interestFraction.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("interest");
        }
        this.interestFraction = interestFraction;
    }

    public BigDecimal personalInterestFraction() { return personalInterestFraction; }

    public void setPersonalInterestFraction(BigDecimal personalInterestFraction) {
        if (personalInterestFraction == null || personalInterestFraction.signum() < 0
                || personalInterestFraction.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("interest");
        }
        this.personalInterestFraction = personalInterestFraction;
    }

    /** Fee-funded interest budget (not an asset); spending is capped by actual treasury. */
    public BigInteger interestPool(String currency) {
        return Money.units(interestPoolExact(currency));
    }

    public BigDecimal interestPoolExact(String currency) {
        return interestPool.getOrDefault(currency, Money.ZERO);
    }

    /**
     * 储蓄总池. Notes: personal book-entry deposits, outstanding checks, the shop operating
     * account and the land-grant account. Commodities: the deposit vault
     * (personal deposits and the land-grant claim on that same vault).
     * Paper (M0) is public cash, not part of this pool. Issuance reserves are not included.
     */
    public BigInteger savingsPool(String currency) {
        CurrencyDef def = need(currency);
        if (def == null) {
            return BigInteger.ZERO;
        }
        BigInteger custody = def.kind() == CurrencyKind.COMMODITY
                ? supply(currency).depositVault()
                : bookSavings(currency);
        return custody;
    }

    /** Customer savings custody at four decimal places; treasury fee income is excluded. */
    public BigDecimal savingsPoolExact(String currency) {
        CurrencyDef def = need(currency);
        if (def == null) {
            return Money.ZERO;
        }
        BigDecimal custody = def.kind() == CurrencyKind.COMMODITY
                ? supply(currency).depositVaultExact()
                : bookSavingsExact(currency);
        return Money.of(custody);
    }

    /** Shop operating balance in {@code currency}. Zero when that slice has never been posted. */
    public BigInteger shopOperating(String currency) {
        return aggregate(shopOperating, currency);
    }

    /** Exact shop balance using the same currency tokens as transferShop. */
    public BigDecimal shopOperatingExact(String currency) {
        if (currency == null) return Money.ZERO;
        if (currency.startsWith("personal:")) {
            try {
                return Money.of(shopPersonal.getOrDefault(
                        UUID.fromString(currency.substring("personal:".length())), Money.ZERO));
            } catch (IllegalArgumentException error) {
                return Money.ZERO;
            }
        }
        CurrencyDef note = primaryVirtual();
        String noteId = note == null ? "note" : note.id();
        if ("diamond".equals(currency) || "copper".equals(currency)) return Money.of(shopExact(currency));
        if (!"note".equals(currency) && !noteId.equals(currency)) return Money.ZERO;
        return Money.of(shopExact(noteId));
    }

    private BigDecimal shopExact(String currency) {
        return Money.of(shopOperating(currency)).add(shopFraction.getOrDefault(currency, Money.ZERO));
    }

    private void addShop(String currency, BigDecimal delta) {
        BigDecimal next = Money.of(shopExact(currency).add(Money.of(delta)));
        if (next.signum() < 0) {
            throw new IllegalStateException("shop underflow");
        }
        BigInteger whole = next.setScale(0, RoundingMode.DOWN).toBigInteger();
        BigDecimal frac = next.subtract(new BigDecimal(whole));
        if (whole.signum() == 0) {
            shopOperating.remove(currency);
        } else {
            shopOperating.put(currency, whole);
        }
        if (frac.signum() == 0) {
            shopFraction.remove(currency);
        } else {
            shopFraction.put(currency, frac);
        }
    }

    /** Land-grant balance in {@code currency}. Zero when that slice has never been posted. */
    public BigInteger landGrant(String currency) {
        return aggregate(landGrant, currency);
    }

    /**
     * Moves savings from one player to another inside the pool. Issuance reserves, the shop
     * operating account, the land-grant account, paper, and the retained-fee slice stay put.
     * A failure changes nothing.
     */
    public TxResult transferSavings(UUID from, UUID to, String currency, BigInteger amount) {
        TxResult rejected = rejectSavings(from, currency, amount);
        if (rejected != null) {
            return rejected;
        }
        if (to == null || from.equals(to)) {
            return TxResult.fail("bad_account", "Savings can only move between two different accounts");
        }
        if (balanceExact(from, currency).compareTo(Money.of(amount)) < 0) {
            return TxResult.fail("insufficient_funds", "Not enough balance");
        }
        debitBalance(from, currency, amount);
        creditBalance(to, currency, amount);
        return TxResult.ok(amount, "Transferred savings");
    }

    /**
     * Shop purchase ({@code playerPaysShop}) or sale. Both legs are the central note inside the
     * savings pool. A failure changes nothing.
     */
    public TxResult transferShopOperating(UUID player, BigInteger amount, boolean playerPaysShop) {
        CurrencyDef note = primaryVirtual();
        if (note == null) {
            return TxResult.fail("unknown_currency", "No central note");
        }
        TxResult rejected = rejectSavings(player, note.id(), amount);
        if (rejected != null) {
            return rejected;
        }
        if (playerPaysShop) {
            if (balanceExact(player, note.id()).compareTo(Money.of(amount)) < 0) {
                return TxResult.fail("insufficient_funds", "Not enough balance");
            }
            debitBalance(player, note.id(), amount);
            addAggregate(shopOperating, note.id(), amount);
        } else {
            if (shopOperating(note.id()).compareTo(amount) < 0) {
                return TxResult.fail("insufficient_shop", "Shop operating account is short");
            }
            addAggregate(shopOperating, note.id(), amount.negate());
            creditBalance(player, note.id(), amount);
        }
        return TxResult.ok(amount, playerPaysShop ? "Shop purchase" : "Shop sale");
    }

    /**
     * Shop settlement at four decimal places. {@code currency} is {@code note} or {@code personal:<uuid>}.
     * A short balance refuses the whole amount.
     */
    public TxResult transferShop(UUID player, String currency, BigDecimal amount, boolean playerPaysShop) {
        BigDecimal posted = amount == null ? Money.ZERO : Money.of(amount);
        if (posted.signum() <= 0) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        if (player == null || currency == null || currency.isBlank()) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        if (currency.startsWith("personal:")) {
            return transferShopPersonal(player, currency.substring("personal:".length()), posted, playerPaysShop);
        }
        CurrencyDef note = primaryVirtual();
        String noteId = note == null ? "note" : note.id();
        if (!noteId.equals(currency) && !"note".equals(currency)) {
            return TxResult.fail("unknown_currency", "Unknown currency");
        }
        if (playerPaysShop) {
            if (balanceExact(player, noteId).compareTo(posted) < 0) {
                return TxResult.fail("insufficient_funds", "Not enough balance");
            }
            debitBalance(player, noteId, posted);
            addShop(noteId, posted);
        } else {
            if (shopExact(noteId).compareTo(posted) < 0 || systemAvailableBalance("shop", noteId).compareTo(posted) < 0) {
                return TxResult.fail("insufficient_shop", "Shop operating account is short");
            }
            addShop(noteId, posted.negate());
            creditBalance(player, noteId, posted);
        }
        return TxResult.ok(Money.units(posted), playerPaysShop ? "Shop purchase" : "Shop sale");
    }

    private TxResult transferShopPersonal(UUID player, String rawId, BigDecimal amount, boolean playerPaysShop) {
        UUID noteId;
        try {
            noteId = UUID.fromString(rawId);
        } catch (IllegalArgumentException error) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        if (!personalTradingAllowed(personalNoteById(noteId))) {
            return TxResult.fail("unknown_currency", "Unknown or paused personal note");
        }
        BigDecimal shop = Money.of(shopPersonal.getOrDefault(noteId, Money.ZERO));
        BigDecimal held = personalHoldingExact(noteId, player);
        if (playerPaysShop) {
            if (held.compareTo(amount) < 0) {
                return TxResult.fail("insufficient_funds", "Not enough balance");
            }
            setPersonalExact(noteId, player, held.subtract(amount));
            shopPersonal.put(noteId, Money.of(shop.add(amount)));
        } else {
            if (shop.compareTo(amount) < 0) {
                return TxResult.fail("insufficient_shop", "Shop operating account is short");
            }
            BigDecimal left = Money.of(shop.subtract(amount));
            if (left.signum() == 0) {
                shopPersonal.remove(noteId);
            } else {
                shopPersonal.put(noteId, left);
            }
            setPersonalExact(noteId, player, held.add(amount));
        }
        return TxResult.ok(Money.units(amount), playerPaysShop ? "Shop purchase" : "Shop sale");
    }

    /** Independently owned central savings; does not include customer custody or issuance reserves. */
    public BigDecimal treasuryExact(String currency) {
        return treasury.getOrDefault(currency, Money.ZERO);
    }

    /** Custody-only figure. Retained fees and treasury are deliberately not included. */
    public BigDecimal custodyExact(String currency) {
        CurrencyDef def = need(currency);
        return def == null ? Money.ZERO : def.kind() == CurrencyKind.COMMODITY
                ? supply(currency).depositVaultExact() : bookSavingsExact(currency);
    }

    /**
     * Explicit operator contribution/withdrawal. Caller must enforce administrator permission.
     * A commodity contribution moves its matching asset out of custody, never another player's claim.
     * This transfers existing units; it never mints money or changes total note liability.
     */
    public TxResult transferTreasury(UUID player, String currency, BigDecimal amount, boolean intoTreasury) {
        CurrencyDef def = need(currency);
        if (player == null || def == null) return TxResult.fail("unknown_currency", "Unknown currency or operator");
        if (amount == null || amount.signum() <= 0 || amount.scale() > Money.SCALE)
            return TxResult.fail("bad_amount", "Invalid amount");
        if (def.kind() == CurrencyKind.COMMODITY && amount.stripTrailingZeros().scale() > 0)
            return TxResult.fail("whole_units_required", "Physical savings transfer requires whole units");
        BigDecimal posted = Money.of(amount);
        if (intoTreasury) {
            if (balanceExact(player, currency).compareTo(posted) < 0)
                return TxResult.fail("insufficient_funds", "Your savings cannot cover this contribution");
            if (def.kind() == CurrencyKind.COMMODITY && supply(currency).depositVaultExact().compareTo(posted) < 0)
                return TxResult.fail("insufficient_deposits", "Custody is short; contribution refused");
            debitBalance(player, currency, posted);
            if (def.kind() == CurrencyKind.COMMODITY) addSavings(currency, posted.negate());
            treasury.put(currency, Money.of(treasuryExact(currency).add(posted)));
        } else {
            if (treasuryExact(currency).compareTo(posted) < 0)
                return TxResult.fail("insufficient_treasury", "Central-owned savings cannot cover this withdrawal");
            treasury.put(currency, Money.of(treasuryExact(currency).subtract(posted)));
            if (def.kind() == CurrencyKind.COMMODITY) addSavings(currency, posted);
            creditBalance(player, currency, posted);
        }
        recomputeOfficialNoteQuote();
        return TxResult.ok(Money.units(posted), intoTreasury ? "Contributed own savings to central treasury" : "Withdrew central treasury to own savings");
    }

    /** Atomic validated treasury reward; callers must enforce OP and resolve online recipients. */
    public TxResult rewardTreasury(java.util.Collection<UUID> recipients, String currency, BigDecimal each) {
        CurrencyDef def = need(currency);
        if (def == null) return TxResult.fail("unknown_currency", "币种没有央行自有储蓄账户，不能凭空发放");
        if (recipients == null || recipients.isEmpty() || recipients.stream().anyMatch(java.util.Objects::isNull))
            return TxResult.fail("no_players", "没有有效在线玩家");
        java.util.Set<UUID> targets = new java.util.LinkedHashSet<>(recipients);
        if (each == null || each.signum() <= 0 || each.scale() > Money.SCALE || each.precision() > 30)
            return TxResult.fail("bad_amount", "数量必须为正数且最多四位小数");
        if (def.kind() == CurrencyKind.COMMODITY && each.stripTrailingZeros().scale() > 0)
            return TxResult.fail("whole_units_required", "实物货币奖励必须为整数");
        BigDecimal total = each.multiply(BigDecimal.valueOf(targets.size()));
        if (treasuryExact(currency).compareTo(total) < 0)
            return TxResult.fail("insufficient_treasury", "央行自有储蓄不足，整批未执行");
        treasury.put(currency, Money.of(treasuryExact(currency).subtract(total)));
        if (def.kind() == CurrencyKind.COMMODITY) addSavings(currency, total);
        for (UUID target : targets) creditBalance(target, currency, each);
        recomputeOfficialNoteQuote();
        return TxResult.ok(Money.units(total), "已向 " + targets.size() + " 名玩家各奖励 "
                + Money.stored(each) + " " + def.name() + "，合计 " + Money.stored(total) + "，来源：央行自有储蓄");
    }

    /** Purchase real issuer-listed inventory with treasury notes, then reward all recipients atomically. */
    public TxResult rewardPersonalFromTreasury(java.util.Collection<UUID> recipients, UUID coin, BigDecimal each) {
        if (recipients == null || recipients.isEmpty() || recipients.stream().anyMatch(java.util.Objects::isNull))
            return TxResult.fail("no_players", "没有有效在线玩家");
        if (each == null || each.signum() <= 0 || each.scale() > Money.SCALE || each.precision() > 30)
            return TxResult.fail("bad_amount", "每人数量必须为正数且最多四位小数");
        PersonalNote note = personalNoteById(coin);
        if (note == null || !personalTradingAllowed(note)) return TxResult.fail("no_price", "个人币暂停或没有可用牌价");
        java.util.Set<UUID> targets = new java.util.LinkedHashSet<>(recipients);
        BigDecimal quantity = each.multiply(BigDecimal.valueOf(targets.size()));
        BigDecimal available = issuerPayoutAvailable(note).min(personalHoldingExact(coin, note.issuer()));
        if (available.compareTo(quantity) < 0) return TxResult.fail("insufficient_offer", "发行者实际挂牌库存不足，整批未执行");
        String central = centralNoteId();
        BigDecimal centralRate = unitsPer(central, "diamond");
        BigDecimal personalPrice = personalDiamondPrice(note);
        if (centralRate == null || centralRate.signum() <= 0 || personalPrice == null || personalPrice.signum() <= 0)
            return TxResult.fail("no_price", "当前没有可执行的央行券认购牌价");
        BigDecimal unitPrice = personalPrice.multiply(centralRate, FxMath.MC);
        // One batch, one total rounded up to the smallest central-note accounting unit.
        BigDecimal cost = quantity.multiply(unitPrice).setScale(Money.SCALE, RoundingMode.CEILING);
        if (treasuryExact(central).compareTo(cost) < 0)
            return TxResult.fail("insufficient_treasury", "央行自有央行券不足，整批未执行；需要 " + Money.stored(cost));
        if (!note.autoListing()) setPersonalOffer(coin, note.issuer(), personalOffered(coin, note.issuer())
                .subtract(quantity.setScale(0, RoundingMode.CEILING).toBigInteger()).max(BigInteger.ZERO));
        setPersonalExact(coin, note.issuer(), personalHoldingExact(coin, note.issuer()).subtract(quantity));
        treasury.put(central, Money.of(treasuryExact(central).subtract(cost)));
        creditBalance(note.issuer(), central, cost);
        for (UUID target : targets) setPersonalExact(coin, target, personalHoldingExact(coin, target).add(each));
        recomputeOfficialNoteQuote();
        return TxResult.ok(Money.units(quantity), "已向 " + targets.size() + " 名玩家各奖励 " + Money.stored(each) + " " + note.name()
                + "；央行现场认购 " + Money.stored(quantity) + "，单价 " + unitPrice.toPlainString()
                + "，实际支付发行者 " + Money.stored(cost) + " " + currency(central).name()
                + "（总价向上取至0.0001），未新增发行");
    }

    public BigDecimal systemFundingBalance(String account) {
        return ordinarySystemBalance(account, centralNoteId());
    }
    /** Note-only display breakdown, never an aggregate valuation of other terminal assets. */
    public JsonObject systemFundingOverview(String account) {
        String currency = centralNoteId();
        BigDecimal ordinary = ordinarySystemBalance(account, currency), settled = systemSettlementBalance(account, currency);
        BigDecimal available = systemAvailableBalance(account, currency), pendingOutgoing = Money.ZERO;
        for (JsonObject receipt : clearingReceipts.values()) {
            if (!deliveryPending(receipt) || !account.equals(receipt.get("account").getAsString())
                    || !currency.equals(receipt.get("currency").getAsString())) continue;
            boolean incoming = !receipt.has("kind") || "land-direct".equals(receipt.get("kind").getAsString())
                    || "shop-direct".equals(receipt.get("kind").getAsString()) && receipt.get("buying").getAsBoolean();
            if (!incoming) pendingOutgoing = pendingOutgoing.add(Money.parse(receipt.get("amount").getAsString()));
        }
        JsonObject row = new JsonObject();
        row.addProperty("ordinary", Money.stored(ordinary));
        row.addProperty("settled", Money.stored(settled));
        row.addProperty("available", Money.stored(available));
        row.addProperty("heldIncoming", Money.stored(ordinary.add(settled).subtract(available).max(BigDecimal.ZERO)));
        row.addProperty("pendingOutgoing", Money.stored(pendingOutgoing));
        return row;
    }
    public JsonArray systemFundingLog() { return systemFundingLog.deepCopy(); }

    /** Existing central-owned notes only; caller also gates the admin session. */
    public TxResult fundSystemAccount(UUID operator, boolean administrator, String account,
                                      BigDecimal amount, BigDecimal expectedTreasury, BigDecimal expectedAccount) {
        if (!administrator || operator == null) return TxResult.fail("permission", "仅管理员可拨款");
        if (!"shop".equals(account) && !"land".equals(account))
            return TxResult.fail("bad_account", "未知系统账户");
        if (amount == null || amount.signum() <= 0 || amount.scale() > Money.SCALE)
            return TxResult.fail("bad_amount", "请输入正数，最多四位小数");
        String currency = centralNoteId();
        BigDecimal source = treasuryExact(currency), target = systemFundingBalance(account);
        if (expectedTreasury == null || expectedAccount == null || source.compareTo(expectedTreasury) != 0
                || target.compareTo(expectedAccount) != 0)
            return TxResult.fail("stale_quote", "账户余额变化，请重新预览");
        if (source.compareTo(amount) < 0) return TxResult.fail("insufficient_treasury", "央行自有央行券不足，不动用玩家存款");
        treasury.put(currency, Money.of(source.subtract(amount)));
        addOrdinarySystem(account, currency, amount);
        JsonObject entry = new JsonObject();
        entry.addProperty("operator", operator.toString());
        entry.addProperty("time", System.currentTimeMillis());
        entry.addProperty("account", account);
        entry.addProperty("amount", Money.stored(amount));
        entry.addProperty("treasuryAfter", Money.stored(treasuryExact(currency)));
        entry.addProperty("accountAfter", Money.stored(systemFundingBalance(account)));
        systemFundingLog.add(entry);
        while (systemFundingLog.size() > 200) systemFundingLog.remove(0);
        return TxResult.ok(Money.units(amount), "系统账户拨款完成");
    }

    /** Issued-note sale proceeds: caller has checked and debited the same payer's physical savings.
     * Keep both the custody asset and its matching savings liability in step.
     */
    private void collectCustodyIntoTreasury(String currency, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || supply(currency).depositVaultExact().compareTo(amount) < 0)
            throw new IllegalStateException("Cannot collect unavailable custody assets");
        addSavings(currency, amount.negate());
        treasury.put(currency, Money.of(treasuryExact(currency).add(amount)));
    }

    /** A quote captured BEFORE the underlying payout changes reserves or circulating supply. */
    private record FractionChange(String currency, BigDecimal physical, BigDecimal notes) {}

    private FractionChange quoteFractionChange(String currency, BigDecimal payout) {
        CurrencyDef def = need(currency);
        if (def == null || def.kind() != CurrencyKind.COMMODITY || payout == null || payout.signum() <= 0) return null;
        BigDecimal fraction = payout.remainder(BigDecimal.ONE);
        if (fraction.signum() == 0) return null;
        BigDecimal price = unitsPer(centralNoteId(), currency);
        if (price == null || price.signum() <= 0) return new FractionChange(currency, fraction, Money.ZERO);
        // Never round away entitlement or round up against the treasury. If exact settlement
        // cannot be expressed at ledger precision, retain the original physical claim instead.
        BigDecimal notes = fraction.multiply(price);
        if (notes.stripTrailingZeros().scale() > Money.SCALE || notes.signum() <= 0)
            notes = Money.ZERO;
        return new FractionChange(currency, fraction, Money.of(notes));
    }

    /**
     * Optional funded change, AFTER the payout is credited. Only the NEW fraction is exchanged;
     * historical fractions are untouched. Failure is a no-op, not a loss of the original claim.
     * This is used only for final payouts, never reversible shop/land delivery transfers.
     */
    private String settleFractionChange(UUID player, FractionChange change) {
        if (change == null) return "";
        String notes = centralNoteId();
        if (change.notes().signum() <= 0 || treasuryExact(notes).compareTo(change.notes()) < 0
                || balanceExact(player, change.currency()).compareTo(change.physical()) < 0
                || supply(change.currency()).depositVaultExact().compareTo(change.physical()) < 0)
            return "; 实物零头权益保留（央行券资金或精确报价不足）";
        // Existing treasury notes change owner. No note issuance, liability adjustment or
        // use of another depositor's note balance is permitted here.
        debitBalance(player, change.currency(), change.physical());
        collectCustodyIntoTreasury(change.currency(), change.physical());
        treasury.put(notes, Money.of(treasuryExact(notes).subtract(change.notes())));
        creditBalance(player, notes, change.notes());
        return "; 实物零头 " + Money.display(change.physical()) + " 已结算为 " + Money.display(change.notes()) + " 央行券";
    }

    /** Moves only central-owned physical assets between treasury and issuance reserves. */
    public TxResult transferReserve(String currency, BigDecimal amount, boolean fromDeposits) {
        CurrencyDef def = need(currency);
        if (def == null) return TxResult.fail("unknown_currency", "Unknown currency");
        if (def.kind() != CurrencyKind.COMMODITY)
            return TxResult.fail("not_commodity", "Cannot use a currency as its own issuance collateral");
        if (amount == null || amount.signum() <= 0 || amount.scale() > Money.SCALE)
            return TxResult.fail("bad_amount", "Invalid amount");
        BigDecimal posted = Money.of(amount);
        Supply self = supply(currency);
        if (fromDeposits) {
            if (treasuryExact(currency).compareTo(posted) < 0)
                return TxResult.fail("insufficient_treasury", "Central-owned savings cannot cover this transfer");
            treasury.put(currency, Money.of(treasuryExact(currency).subtract(posted)));
            self.addReserve(posted);
        } else {
            if (self.freeReserveExact().compareTo(posted) < 0)
                return TxResult.fail("insufficient_reserves", "Issuance reserves cannot cover this transfer");
            self.addReserve(posted.negate());
            treasury.put(currency, Money.of(treasuryExact(currency).add(posted)));
        }
        recomputeOfficialNoteQuote();
        return TxResult.ok(Money.units(posted), fromDeposits ? "Reserve from central treasury" : "Reserve to central treasury");
    }

    /**
     * Server land sale. Debits the payer's savings and credits 土地出让账户.
     * A player-to-player land trade must use {@link #transferSavings} instead.
     */
    /** Executable system-land quote. Physical tender stays whole; exact remainder is paid in notes. */
    public JsonObject quoteLandPurchase(String denomination, BigInteger total, String tender) {
        JsonObject out = new JsonObject(); out.addProperty("success", false);
        if (total == null || total.signum() <= 0 || total.bitLength() > 100) return out;
        BigDecimal value = landCentralValue(denomination, new BigDecimal(total));
        BigDecimal unit = landCentralValue(tender, BigDecimal.ONE);
        if (value == null || unit == null || value.signum() <= 0 || unit.signum() <= 0) return out;
        value = value.setScale(Money.SCALE, RoundingMode.UP);
        int scale = tender.equals("note") || tender.startsWith("personal:") ? Money.SCALE : 0;
        boolean personal = tender.startsWith("personal:");
        BigDecimal quantity = value.divide(unit, scale, personal ? RoundingMode.UP : RoundingMode.DOWN);
        BigDecimal paid = quantity.signum() == 0 ? Money.ZERO : landCentralValue(tender, quantity);
        // Personal coins are divisible. Quantize in the selected coin, never charge a second currency.
        // Recursive clearing can round the collateral leg; retry a bounded number of minimum units.
        for (int i = 0; personal && paid != null && paid.compareTo(value) < 0 && i < 32; i++) {
            quantity = quantity.add(new BigDecimal("0.0001"));
            paid = landCentralValue(tender, quantity);
        }
        if (paid == null || (personal ? paid.compareTo(value) != 0 : paid.compareTo(value) > 0)) {
            out.addProperty("code", "inexact_tender");
            out.addProperty("message", "所选个人币在四位精度内无法精确结算该价格，请调整数量或选择其他币种；不会补扣央行券。");
            return out;
        }
        BigDecimal topup = personal ? Money.ZERO : value.subtract(paid).setScale(Money.SCALE, RoundingMode.UP);
        out.addProperty("denomination", denomination); out.addProperty("total", total.toString());
        out.addProperty("paymentCurrency", tender); out.addProperty("quantity", quantity.toPlainString());
        out.addProperty("noteTopup", topup.toPlainString()); out.addProperty("centralValue", value.toPlainString());
        // Include the actual executable collateral legs, not merely a displayed exchange rate.
        String backing = tender.startsWith("personal:") && quantity.signum() > 0
                ? quotePersonalClearing(nestedId(tender), quantity).toString() : "";
        out.addProperty("fingerprint", denomination + "|" + total + "|" + tender + "|" + quantity + "|" + topup + "|" + value + "|" + backing);
        out.addProperty("success", true); return out;
    }
    private BigDecimal landCentralValue(String currency, BigDecimal quantity) {
        if (currency == null || quantity == null || quantity.signum() <= 0) return null;
        if (currency.equals("note")) return quantity;
        if (currency.startsWith("personal:")) {
            UUID id = nestedId(currency); if (id == null) return null;
            ClearingQuote q = quotePersonalClearing(id, quantity);
            if (!q.success()) return null;
            BigDecimal rate = q.currency().equals("note") ? BigDecimal.ONE : unitsPer("note", q.currency());
            return rate == null ? null : q.amount().multiply(rate);
        }
        if (!baseAnchor(currency)) return null;
        BigDecimal rate = unitsPer("note", currency);
        return rate == null ? null : quantity.multiply(rate);
    }
    public TxResult payLandPurchase(UUID payer, String denomination, BigInteger total, String tender, String fingerprint, String reference) {
        String signature = payer + "|" + denomination + "|" + total + "|" + tender + "|" + fingerprint;
        JsonObject prior = clearingReceipts.get(reference);
        if (prior != null) return signature.equals(prior.get("signature").getAsString()) && "settled".equals(prior.get("status").getAsString())
                ? TxResult.ok(total, "Already paid") : TxResult.fail("reference_conflict", "");
        JsonObject q = quoteLandPurchase(denomination, total, tender);
        if (!q.get("success").getAsBoolean() || !q.get("fingerprint").getAsString().equals(fingerprint)) return TxResult.fail("price_moved", "请重新报价");
        BigDecimal quantity = new BigDecimal(q.get("quantity").getAsString()), topup = new BigDecimal(q.get("noteTopup").getAsString());
        BigDecimal notesRequired = topup.add(tender.equals("note") ? quantity : BigDecimal.ZERO);
        if (balanceExact(payer, "note").compareTo(notesRequired) < 0) return TxResult.fail("insufficient_funds", "央行券不足以补齐尾差");
        if (!tender.startsWith("personal:") && !tender.equals("note") && balanceExact(payer, tender).compareTo(quantity) < 0)
            return TxResult.fail("insufficient_funds", "所选币种储蓄不足");
        if (quantity.signum() > 0) {
            TxResult paid = tender.startsWith("personal:") ? settlePersonalToSystem(payer, nestedId(tender), quantity, "land", reference + ":t")
                    : landDirect(payer, tender, quantity, reference + ":t");
            if (!paid.success()) return paid;
        }
        if (topup.signum() > 0) { TxResult paid = landDirect(payer, "note", topup, reference + ":n"); if (!paid.success()) return paid; }
        JsonObject row = new JsonObject(); row.addProperty("kind", "land-purchase"); row.addProperty("signature", signature);
        row.addProperty("original", payer + "|" + denomination + "|" + total);
        row.addProperty("tenderLeg", quantity.signum() > 0); row.addProperty("noteLeg", topup.signum() > 0);
        row.addProperty("reference", reference); row.addProperty("status", "settled"); row.addProperty("pendingDelivery", true);
        row.addProperty("account", "land"); row.addProperty("currency", "note"); row.addProperty("amount", "0"); row.add("legs", new JsonArray());
        clearingReceipts.put(reference, row); return TxResult.ok(total, "Land payment settled");
    }
    private TxResult landDirect(UUID payer, String currency, BigDecimal amount, String reference) {
        if (balanceExact(payer, currency).compareTo(amount) < 0) return TxResult.fail("insufficient_funds", "储蓄不足，实物尾差需央行券补齐");
        debitBalance(payer, currency, amount);
        settledAssets.computeIfAbsent("land", unused -> new LinkedHashMap<>()).merge(currency, amount, BigDecimal::add);
        JsonObject row = new JsonObject(); row.addProperty("kind", "land-direct"); row.addProperty("payer", payer.toString());
        row.addProperty("currency", currency); row.addProperty("amount", amount.toPlainString()); row.addProperty("account", "land");
        row.addProperty("status", "settled"); row.addProperty("pendingDelivery", true); row.add("legs", new JsonArray());
        clearingReceipts.put(reference, row); return TxResult.ok(Money.units(amount), "");
    }
    public boolean hasLandPurchase(String reference) { JsonObject r = clearingReceipts.get(reference); return r != null && r.has("kind") && "land-purchase".equals(r.get("kind").getAsString()); }
    public TxResult reverseLandPurchase(UUID payer, String denomination, BigInteger total, String reference) {
        JsonObject row = clearingReceipts.get(reference);
        if (!hasLandPurchase(reference) || !(payer + "|" + denomination + "|" + total).equals(row.get("original").getAsString())) return TxResult.fail("reference_conflict", "");
        if ("reversed".equals(row.get("status").getAsString())) return TxResult.ok(total, "Already refunded");
        if (!deliveryPending(row)) return TxResult.fail("completed", "Delivered land cannot be refunded");
        for (String suffix : java.util.List.of(":t", ":n")) {
            JsonObject leg = clearingReceipts.get(reference + suffix); if (leg == null) continue;
            if (leg.has("kind") && "land-direct".equals(leg.get("kind").getAsString())) {
                String currency = leg.get("currency").getAsString(); BigDecimal amount = new BigDecimal(leg.get("amount").getAsString());
                BigDecimal held = systemSettlementBalance("land", currency);
                if (held.compareTo(amount) < 0) return TxResult.fail("insufficient_system", "");
                settledAssets.get("land").put(currency, held.subtract(amount)); creditBalance(payer, currency, amount);
                leg.addProperty("status", "reversed"); leg.addProperty("pendingDelivery", false);
            } else { TxResult reversed = reversePersonalSettlement(reference + suffix); if (!reversed.success()) return reversed; }
        }
        row.addProperty("status", "reversed"); row.addProperty("pendingDelivery", false); return TxResult.ok(total, "Land refunded");
    }

    public TxResult creditLandGrant(UUID payer, String currency, BigInteger amount) {
        TxResult rejected = rejectSavings(payer, currency, amount);
        if (rejected != null) {
            return rejected;
        }
        if (balance(payer, currency).compareTo(amount) < 0) {
            return TxResult.fail("insufficient_funds", "Not enough balance");
        }
        debitBalance(payer, currency, amount);
        addAggregate(landGrant, currency, amount);
        return TxResult.ok(amount, "Land-grant credit");
    }

    /** Reverses {@link #creditLandGrant}. A short land-grant balance pays nothing. */
    public TxResult refundLandGrant(UUID payer, String currency, BigInteger amount) {
        TxResult rejected = rejectSavings(payer, currency, amount);
        if (rejected != null) {
            return rejected;
        }
        if (landGrant(currency).compareTo(amount) < 0) {
            return TxResult.fail("insufficient_funds", "Land-grant account is short");
        }
        addAggregate(landGrant, currency, amount.negate());
        creditBalance(payer, currency, amount);
        return TxResult.ok(amount, "Land-grant refund");
    }

    private TxResult rejectSavings(UUID player, String currency, BigInteger amount) {
        if (player == null) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        if (currency == null || need(currency) == null) {
            return TxResult.fail("unknown_currency", "Unknown currency");
        }
        if (amount == null || amount.signum() <= 0) {
            return TxResult.fail("bad_amount", "Invalid amount");
        }
        return null;
    }

    public long lastInterestDay() { return lastInterestDay; }

    /**
     * Credits one demand-deposit day for each Minecraft day crossed.
     * The first call only records the day, so a newly loaded world does not pay for the past.
     */
    public InterestDay accrueInterest(long worldDay) {
        if (lastInterestDay < 0) {
            lastInterestDay = worldDay;
            anchorPersonalInterest(worldDay);
            return new InterestDay(true, List.of());
        }
        if (worldDay <= lastInterestDay) {
            return new InterestDay(false, List.of());
        }
        for (PersonalNote note : personalNotes.values()) {
            if (note.interestDay() < 0) {
                note.setInterestDay(lastInterestDay);
            }
        }
        List<InterestCredit> credits = new ArrayList<>();
        long days = worldDay - lastInterestDay;
        for (long i = 0; i < days; i++) {
            credits.addAll(settleOneDay());
            for (PersonalNote note : personalNotes.values()) {
                credits.addAll(settlePersonalDay(note));
            }
        }
        lastInterestDay = worldDay;
        for (PersonalNote note : personalNotes.values()) {
            note.setInterestDay(worldDay);
        }
        return new InterestDay(true, credits);
    }

    private void anchorPersonalInterest(long worldDay) {
        for (PersonalNote note : personalNotes.values()) {
            if (note.interestDay() < 0) {
                note.setInterestDay(worldDay);
            }
        }
    }

    private List<InterestCredit> settleOneDay() {
        CurrencyDef note = primaryVirtual();
        if (note == null || interestFraction.signum() <= 0) {
            return List.of();
        }
        Map<UUID, BigDecimal> due = new LinkedHashMap<>();
        BigDecimal total = Money.ZERO;
        for (UUID player : new ArrayList<>(accounts.keySet())) {
            BigDecimal raw = accrued(balanceExact(player, note.id()), interestFraction,
                    interestDust.getOrDefault(player, Money.ZERO));
            if (raw.signum() > 0) {
                due.put(player, raw);
                total = total.add(raw);
            }
        }
        BigDecimal shopDue = accrued(shopExact(note.id()), interestFraction,
                shopInterestDust.getOrDefault(note.id(), Money.ZERO));
        BigDecimal landDue = accrued(Money.of(landGrant(note.id())), interestFraction,
                landInterestDust.getOrDefault(note.id(), Money.ZERO));
        total = Money.of(total.add(shopDue).add(landDue));
        BigDecimal pool = interestPoolExact(note.id()).min(treasuryExact(note.id()));
        if (total.signum() <= 0 || pool.signum() <= 0) {
            due.forEach((id, raw) -> rememberDust(interestDust, id, raw));
            rememberDust(shopInterestDust, note.id(), shopDue);
            rememberDust(landInterestDust, note.id(), landDue);
            return List.of();
        }
        List<InterestCredit> paid = new ArrayList<>();
        BigDecimal spent = Money.ZERO;
        boolean full = pool.compareTo(total) >= 0;
        for (Map.Entry<UUID, BigDecimal> entry : due.entrySet()) {
            BigDecimal share = interestShare(entry.getValue(), pool, total, full);
            if (share.signum() <= 0) {
                rememberDust(interestDust, entry.getKey(), entry.getValue());
                continue;
            }
            creditBalance(entry.getKey(), note.id(), share);
            paid.add(new InterestCredit(entry.getKey(), Money.units(share), null));
            spent = spent.add(share);
            rememberDust(interestDust, entry.getKey(), entry.getValue().subtract(share));
        }
        spent = spent.add(paySavingsInterest(note.id(), shopOperating, shopInterestDust, shopDue, full, pool, total));
        spent = spent.add(paySavingsInterest(note.id(), landGrant, landInterestDust, landDue, full, pool, total));
        addPool(note.id(), spent.negate());
        return paid;
    }

    /** One day's demand-deposit interest, already rounded to four decimal places. */
    private static BigDecimal accrued(BigDecimal balance, BigDecimal rate, BigDecimal carried) {
        if (balance == null || balance.signum() <= 0 || rate == null || rate.signum() <= 0) {
            return Money.of(carried);
        }
        return Money.of(balance.multiply(rate)
                .divide(new BigDecimal("365"), Money.SCALE, RoundingMode.HALF_UP)
                .add(Money.of(carried)));
    }

    private static BigDecimal interestShare(BigDecimal due, BigDecimal pool, BigDecimal total, boolean full) {
        if (due == null || due.signum() <= 0) {
            return Money.ZERO;
        }
        if (full) {
            return Money.of(due);
        }
        return Money.of(due.multiply(pool).divide(total, Money.SCALE, RoundingMode.DOWN));
    }

    private static void rememberDust(Map<UUID, BigDecimal> dust, UUID id, BigDecimal remain) {
        BigDecimal posted = Money.of(remain);
        if (posted.signum() == 0) {
            dust.remove(id);
        } else {
            dust.put(id, posted);
        }
    }

    private static void rememberDust(Map<String, BigDecimal> dust, String id, BigDecimal remain) {
        BigDecimal posted = Money.of(remain);
        if (posted.signum() == 0) {
            dust.remove(id);
        } else {
            dust.put(id, posted);
        }
    }

    /**
     * Shop and land-grant balances are whole notes. A fractional day's interest stays in the dust
     * until it reaches one note, and the pool is charged only for the note that was booked.
     */
    private BigDecimal paySavingsInterest(String currency, Map<String, BigInteger> book, Map<String, BigDecimal> dust,
                                          BigDecimal due, boolean full, BigDecimal pool, BigDecimal total) {
        BigDecimal share = interestShare(due, pool, total, full);
        BigInteger whole = Money.units(share);
        if (whole.signum() <= 0) {
            rememberDust(dust, currency, due);
            return Money.ZERO;
        }
        addAggregate(book, currency, whole);
        rememberDust(dust, currency, due.subtract(Money.of(whole)));
        return Money.of(whole);
    }

    private List<InterestCredit> settlePersonalDay(PersonalNote series) {
        if (!personalTradingAllowed(series) || personalInterestFraction.signum() <= 0) {
            return List.of();
        }
        Map<UUID, BigInteger> holdings = personalHoldings.get(series.id());
        if (holdings == null || holdings.isEmpty()) {
            return List.of();
        }
        Map<UUID, BigDecimal> dust = personalInterestDust.computeIfAbsent(series.id(), id -> new LinkedHashMap<>());
        Map<UUID, BigInteger> due = new LinkedHashMap<>();
        BigInteger total = BigInteger.ZERO;
        for (UUID player : new ArrayList<>(holdings.keySet())) {
            BigInteger balance = personalHolding(series.id(), player);
            BigDecimal carried = Money.of(dust.getOrDefault(player, BigDecimal.ZERO));
            BigDecimal raw = Money.of(balance).multiply(personalInterestFraction)
                    .divide(new BigDecimal("365"), Money.SCALE, RoundingMode.HALF_UP)
                    .add(carried);
            raw = Money.of(raw);
            BigInteger whole = raw.setScale(0, RoundingMode.DOWN).toBigInteger();
            if (whole.signum() < 0) {
                whole = BigInteger.ZERO;
            }
            BigDecimal remain = Money.of(raw.subtract(new BigDecimal(whole)));
            if (remain.signum() == 0) {
                dust.remove(player);
            } else {
                dust.put(player, remain);
            }
            if (whole.signum() > 0) {
                due.put(player, whole);
                total = total.add(whole);
            }
        }
        BigInteger pool = series.interestPool();
        if (total.signum() <= 0 || pool.signum() <= 0) {
            return List.of();
        }
        List<InterestCredit> paid = new ArrayList<>();
        BigInteger spent = BigInteger.ZERO;
        boolean full = pool.compareTo(total) >= 0;
        for (Map.Entry<UUID, BigInteger> entry : due.entrySet()) {
            BigInteger share = full ? entry.getValue() : entry.getValue().multiply(pool).divide(total);
            if (share.signum() <= 0) {
                continue;
            }
            addHolding(series.id(), entry.getKey(), share);
            series.setCirculation(series.circulation().add(share));
            paid.add(new InterestCredit(entry.getKey(), share, series.id()));
            spent = spent.add(share);
        }
        series.addInterestPool(spent.negate());
        return paid;
    }

    /**
     * Moves a fee, in this series' own notes, out of a book-entry holding into its interest pool.
     * Circulation falls with the holding. Issuance reserves and the issuer's commodity reserve stay put.
     */
    private void retainTradeFee(PersonalNote note, UUID holder, BigInteger traded) {
        if (note == null || note.bankrupt() || traded == null || traded.signum() <= 0 || feeFraction.signum() <= 0) {
            return;
        }
        BigInteger fee = new BigDecimal(traded).multiply(feeFraction, FxMath.MC).setScale(0, RoundingMode.FLOOR).toBigInteger();
        if (fee.signum() <= 0) {
            return;
        }
        BigInteger take = fee.min(personalHolding(note.id(), holder));
        if (take.signum() <= 0) {
            return;
        }
        addHolding(note.id(), holder, take.negate());
        BigInteger next = note.circulation().subtract(take);
        note.setCirculation(next.signum() < 0 ? BigInteger.ZERO : next);
        note.addInterestPool(take);
    }

    /** Test and migration aid: posts retained fees without a trade. */
    void postRetainedFee(String currency, BigInteger amount) {
        if (amount != null && amount.signum() > 0) {
            addPool(currency, amount);
        }
    }

    private void addPool(String currency, BigInteger delta) {
        addPool(currency, Money.of(delta));
    }

    private void addPool(String currency, BigDecimal delta) {
        BigDecimal change = Money.of(delta);
        BigDecimal budget = Money.of(interestPoolExact(currency).add(change));
        BigDecimal owned = Money.of(treasuryExact(currency).add(change));
        if (budget.signum() < 0 || owned.signum() < 0)
            throw new IllegalStateException("fee-funded interest budget or treasury underflow");
        CurrencyDef def = need(currency);
        if (def == null) throw new IllegalStateException("unknown fee currency");
        // Fee callers extinguished the gross note payment; the retained fee remains issued
        // as a treasury-owned note. Paying interest subsequently transfers it, not reissues it.
        if (change.signum() > 0 && def.kind() != CurrencyKind.COMMODITY)
            supply(currency).addLiability(change);
        treasury.put(currency, owned);
        if (budget.signum() == 0) interestPool.remove(currency);
        else interestPool.put(currency, budget);
    }

    public void setAlpha(String id, BigDecimal permille) { this.alphas.put(id, permille); }
    public void setClampMin(BigDecimal clampMin) { this.clampMin = clampMin; }
    public void setClampMax(BigDecimal clampMax) { this.clampMax = clampMax; }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }

    public TxResult setFrozenIfMatches(boolean expected, boolean desired) {
        if (frozen != expected) return TxResult.fail("stale_preview", "兑付状态已被修改，请重新确认");
        frozen = desired;
        return TxResult.ok(BigInteger.ZERO, desired ? "已暂停兑付" : "已恢复兑付");
    }
    public void setGlobalSidebar(boolean v) { this.globalSidebar = v; }
    public void setGlobalBossbar(boolean v) { this.globalBossbar = v; }
    public void setGlobalTab(boolean v) { this.globalTab = v; }
    public void setGlobalHologram(boolean v) { this.globalHologram = v; }
    public void setJoinMessage(boolean v) { this.joinMessage = v; }

    /**
     * Adjusts issuance reserves only. Savings balances and the deposit vault stay put.
     * For the central note, this moves the FX seed, not public M1.
     */
    public void setOpeningReserve(String currency, BigInteger opening) {
        if (need(currency) == null) {
            throw new IllegalArgumentException("unknown");
        }
        if (opening == null || opening.signum() < 0) {
            throw new IllegalArgumentException("negative");
        }
        Supply self = supply(currency);
        BigInteger newReserve = self.reserve().add(opening.subtract(self.anchor()));
        if (newReserve.signum() < 0) {
            throw new IllegalArgumentException("negative");
        }
        if (newReserve.compareTo(self.locked()) < 0) {
            throw new IllegalArgumentException("below_locked");
        }
        self.setAnchor(opening);
        self.setReserve(newReserve);
        recomputeOfficialNoteQuote();
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
        recomputeOfficialNoteQuote();
    }

    /**
     * Whole copper ingots for one diamond, from the live quote. Null when the books have no quote.
     * There is no fallback par.
     */
    public BigInteger copperPerDiamond() {
        recomputeOfficialCopperQuote();
        if (liveCopperPerDiamond == null || liveCopperPerDiamond.signum() <= 0) {
            return null;
        }
        BigInteger rounded = liveCopperPerDiamond.setScale(0, RoundingMode.HALF_UP).toBigInteger();
        return rounded.signum() < 1 ? null : rounded;
    }

    public BigInteger personalReserve(UUID player, String currency) {
        Map<String, BigInteger> row = personalReserves.get(player);
        if (row == null) {
            return BigInteger.ZERO;
        }
        return row.getOrDefault(currency, BigInteger.ZERO);
    }

    /** Known bank customers, including offline holders and reserve-only accounts. */
    public java.util.Set<UUID> customerIds() {
        java.util.Set<UUID> ids = new java.util.HashSet<>(accounts.keySet());
        ids.addAll(personalReserves.keySet());
        ids.addAll(centralReserveAccounts.keySet());
        personalNotes.values().forEach(note -> ids.add(note.issuer()));
        personalHoldings.values().forEach(row -> ids.addAll(row.keySet()));
        holdingFraction.values().forEach(row -> ids.addAll(row.keySet()));
        return java.util.Set.copyOf(ids);
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
        PersonalNote own = personalNote(player);
        if (own != null && noteHasPending(own.id())) throw new IllegalStateException("delivery_pending");
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
        return Money.units(personalHoldingExact(noteId, player));
    }

    public BigDecimal personalHoldingExact(UUID noteId, UUID player) {
        BigInteger whole = BigInteger.ZERO;
        Map<UUID, BigInteger> row = personalHoldings.get(noteId);
        if (row != null) {
            whole = row.getOrDefault(player, BigInteger.ZERO);
        }
        BigDecimal frac = Money.ZERO;
        Map<UUID, BigDecimal> fractions = holdingFraction.get(noteId);
        if (fractions != null) {
            frac = fractions.getOrDefault(player, Money.ZERO);
        }
        return Money.of(Money.of(whole).add(frac));
    }

    private void setPersonalExact(UUID noteId, UUID player, BigDecimal exact) {
        BigDecimal posted = Money.of(exact);
        if (posted.signum() < 0) {
            throw new IllegalStateException("personal note underflow");
        }
        BigInteger whole = posted.setScale(0, RoundingMode.DOWN).toBigInteger();
        BigDecimal frac = posted.subtract(new BigDecimal(whole));
        Map<UUID, BigInteger> row = personalHoldings.computeIfAbsent(noteId, id -> new LinkedHashMap<>());
        if (whole.signum() == 0) {
            row.remove(player);
            if (row.isEmpty()) {
                personalHoldings.remove(noteId);
            }
        } else {
            row.put(player, whole);
        }
        limitPersonalOffer(noteId, player, whole);
        Map<UUID, BigDecimal> fractions = holdingFraction.computeIfAbsent(noteId, id -> new LinkedHashMap<>());
        if (frac.signum() == 0) {
            fractions.remove(player);
            if (fractions.isEmpty()) {
                holdingFraction.remove(noteId);
            }
        } else {
            fractions.put(player, frac);
        }
    }

    private BigDecimal ordinarySystemBalance(String account, String currency) {
        if ("shop".equals(account)) return shopExact(currency);
        if ("land".equals(account)) return landGrantExact(currency);
        return Money.ZERO;
    }
    public BigDecimal landGrantExact(String currency) {
        return Money.of(new BigDecimal(aggregate(landGrant, currency)).add(landGrantFraction.getOrDefault(currency, Money.ZERO)));
    }
    private void addOrdinarySystem(String account, String currency, BigDecimal delta) {
        if ("shop".equals(account)) { addShop(currency, delta); return; }
        if (!"land".equals(account)) throw new IllegalArgumentException("system account");
        BigDecimal next = Money.of(landGrantExact(currency).add(delta));
        if (next.signum() < 0) throw new IllegalStateException("land account underflow");
        BigInteger whole = Money.units(next); landGrant.put(currency, whole);
        landGrantFraction.put(currency, next.subtract(new BigDecimal(whole)));
    }
    private static String payoutFingerprint(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private BigDecimal issuerPayoutAvailable(PersonalNote note) {
        if (note == null || !personalTradingAllowed(note)) return Money.ZERO;
        return note.autoListing() ? personalHoldingExact(note.id(), note.issuer())
                : new BigDecimal(personalOffered(note.id(), note.issuer()));
    }
    /** Spending capacity only, not an executable quote for the entire system balance. */
    public BigDecimal systemPersonalPayoutCapacity(String account, UUID coin) {
        PersonalNote note = personalNoteById(coin);
        BigDecimal available = issuerPayoutAvailable(note), centralRate = unitsPer("note", "diamond");
        if (available.signum() <= 0 || centralRate == null || centralRate.signum() <= 0) return Money.ZERO;
        BigDecimal price = personalDiamondPrice(note).multiply(centralRate, FxMath.MC);
        if (price.signum() <= 0) return Money.ZERO;
        return systemAvailableBalance(account, "note").divide(price, Money.SCALE, RoundingMode.DOWN).min(available);
    }
    public JsonObject quoteSystemPayout(UUID coin, BigDecimal budget) { return quoteIssuerPayout("shop", coin, budget); }
    public JsonObject quoteSystemPayout(String account, UUID coin, BigDecimal budget) { return quoteIssuerPayout(account, coin, budget); }
    public TxResult executeSystemPayout(UUID player, UUID coin, BigDecimal budget, String fingerprint, String reference) {
        return payIssuerOffer("shop", player, coin, budget, fingerprint, reference);
    }
    public TxResult executeSystemPayout(String account, UUID player, UUID coin, BigDecimal budget, String fingerprint, String reference) {
        return payIssuerOffer(account, player, coin, budget, fingerprint, reference);
    }

    public JsonObject quoteIssuerPayout(UUID coin, BigDecimal centralBudget) { return quoteIssuerPayout("shop", coin, centralBudget); }
    /** Existing issuer offer only: no new issuance, recovery inventory or collateral redemption. */
    public JsonObject quoteIssuerPayout(String account, UUID coin, BigDecimal centralBudget) {
        JsonObject quote = new JsonObject();
        if ((!"shop".equals(account) && !"land".equals(account)) || centralBudget == null || centralBudget.signum() <= 0
                || centralBudget.scale() > Money.SCALE || personalNoteById(coin) == null) {
            quote.addProperty("success", false); quote.addProperty("code", "invalid"); return quote;
        }
        PersonalNote note = personalNoteById(coin);
        BigDecimal budget = Money.of(centralBudget), held = personalHoldingExact(coin, note.issuer());
        BigDecimal available = issuerPayoutAvailable(note);
        BigDecimal centralRate = unitsPer("note", "diamond");
        BigDecimal price = personalTradingAllowed(note) && centralRate != null && centralRate.signum() > 0
                ? personalDiamondPrice(note).multiply(centralRate, FxMath.MC) : BigDecimal.ZERO;
        BigDecimal quantity = price.signum() <= 0 ? Money.ZERO : budget.divide(price, Money.SCALE, RoundingMode.DOWN);
        BigDecimal proceeds = quantity.signum() == 0 ? Money.ZERO : price.multiply(quantity).setScale(Money.SCALE, RoundingMode.CEILING);
        String failure = price.signum() <= 0 ? "no_price"
                : quantity.signum() <= 0 || proceeds.compareTo(budget) != 0 ? "precision"
                : quantity.compareTo(available) > 0 ? "insufficient_offer" : null;
        if (failure != null) {
            quote.addProperty("success", false); quote.addProperty("code", failure);
            quote.addProperty("message", "个人币无法足额支付，请调整数量或选择其他收款币种；不会自动改用央行券。");
            return quote;
        }
        BigDecimal remainder = Money.ZERO;
        quote.addProperty("success", true); quote.addProperty("code", "personal_only");
        quote.addProperty("account", account); quote.addProperty("noteId", coin.toString());
        quote.addProperty("name", note.name()); quote.addProperty("issuer", note.issuer().toString());
        quote.addProperty("listingMode", note.autoListing() ? "auto" : "manual");
        quote.addProperty("centralBudget", Money.stored(budget)); quote.addProperty("unitPrice", price.stripTrailingZeros().toPlainString());
        quote.addProperty("available", Money.stored(available)); quote.addProperty("issuerHeld", Money.stored(held));
        quote.addProperty("privateAmount", Money.stored(quantity)); quote.addProperty("issuerProceeds", Money.stored(proceeds));
        quote.addProperty("centralRemainder", Money.stored(remainder));
        quote.addProperty("fingerprint", payoutFingerprint(quote.toString()));
        return quote;
    }
    public TxResult payIssuerOffer(UUID player, UUID coin, BigDecimal budget, String fingerprint, String reference) {
        return payIssuerOffer("shop", player, coin, budget, fingerprint, reference);
    }
    /** Prepare all real assets in one ledger transaction; delivery completion publishes all beneficiaries together. */
    public TxResult payIssuerOffer(String account, UUID player, UUID coin, BigDecimal budget, String fingerprint, String reference) {
        if (player == null || coin == null || budget == null || fingerprint == null || reference == null || reference.isBlank() || reference.length() > 160)
            return TxResult.fail("invalid", "Invalid payout");
        String signature = "issuer-payout|" + account + "|" + player + "|" + coin + "|" + budget.stripTrailingZeros().toPlainString() + "|" + fingerprint;
        JsonObject previous = clearingReceipts.get(reference);
        if (previous != null) return signature.equals(previous.get("signature").getAsString()) && "settled".equals(previous.get("status").getAsString())
                ? TxResult.ok(Money.units(budget), "Already prepared") : TxResult.fail("reference_conflict", "Payment reference conflict");
        JsonObject quote = quoteIssuerPayout(account, coin, budget);
        if (!quote.get("success").getAsBoolean()) return TxResult.fail(quote.get("code").getAsString(), "Cannot quote payout");
        if (!fingerprint.equals(quote.get("fingerprint").getAsString())) return TxResult.fail("quote_changed", "Payout combination changed; confirm a fresh preview");
        BigDecimal total = Money.of(budget);
        if (systemAvailableBalance(account, "note").compareTo(total) < 0) return TxResult.fail("insufficient_system", "System needs real central notes for the entire payout");
        PersonalNote note = personalNoteById(coin); BigDecimal quantity = Money.parse(quote.get("privateAmount").getAsString());
        BigInteger listedBefore = personalOffered(coin, note.issuer());
        BigDecimal fromOrdinary = ordinarySystemBalance(account, "note").min(total), fromSettled = total.subtract(fromOrdinary);
        if (fromOrdinary.signum() > 0) addOrdinarySystem(account, "note", fromOrdinary.negate());
        if (fromSettled.signum() > 0) settledAssets.get(account).put("note", Money.of(systemSettlementBalance(account, "note").subtract(fromSettled)));
        if (quantity.signum() > 0) {
            if (!note.autoListing()) setPersonalOffer(coin, note.issuer(), listedBefore.subtract(quantity.setScale(0, RoundingMode.CEILING).toBigInteger()).max(BigInteger.ZERO));
            setPersonalExact(coin, note.issuer(), personalHoldingExact(coin, note.issuer()).subtract(quantity));
        }
        JsonObject receipt = quote.deepCopy(); receipt.addProperty("kind", "issuer-offer-payout");
        receipt.addProperty("reference", reference); receipt.addProperty("signature", signature); receipt.addProperty("payer", player.toString());
        receipt.addProperty("currency", "note"); receipt.addProperty("amount", Money.stored(total));
        receipt.addProperty("fromOrdinary", Money.stored(fromOrdinary)); receipt.addProperty("fromSettled", Money.stored(fromSettled));
        receipt.addProperty("listingReserved", note.autoListing() ? "0" : quantity.setScale(0, RoundingMode.CEILING).toPlainString());
        receipt.addProperty("listedBefore", listedBefore.toString()); receipt.addProperty("timestamp", System.currentTimeMillis());
        receipt.addProperty("status", "settled"); receipt.addProperty("pendingDelivery", true);
        JsonArray legs = new JsonArray();
        if (quantity.signum() > 0) {
            JsonObject leg = new JsonObject(); leg.addProperty("noteId", coin.toString()); leg.addProperty("anchor", note.anchor());
            leg.addProperty("notes", quantity.toString()); leg.addProperty("reserve", "0"); legs.add(leg);
        }
        receipt.add("legs", legs); clearingReceipts.put(reference, receipt);
        return TxResult.ok(Money.units(total), "Confirmed assets held pending goods delivery");
    }
    private TxResult completeIssuerPayout(JsonObject receipt) {
        UUID coin = UUID.fromString(receipt.get("noteId").getAsString()), issuer = UUID.fromString(receipt.get("issuer").getAsString());
        UUID player = UUID.fromString(receipt.get("payer").getAsString()); BigDecimal quantity = Money.parse(receipt.get("privateAmount").getAsString());
        BigDecimal proceeds = Money.parse(receipt.get("issuerProceeds").getAsString()), remainder = Money.parse(receipt.get("centralRemainder").getAsString());
        // None of these funds were previously credited to a spendable beneficiary account.
        if (quantity.signum() > 0) setPersonalExact(coin, player, personalHoldingExact(coin, player).add(quantity));
        if (proceeds.signum() > 0) creditBalance(issuer, "note", proceeds);
        if (remainder.signum() > 0) creditBalance(player, "note", remainder);
        receipt.addProperty("pendingDelivery", false); receipt.addProperty("completedAt", System.currentTimeMillis());
        return TxResult.ok(Money.units(proceeds.add(remainder)), "Issuer purchase and player payout completed");
    }
    private TxResult rollbackIssuerPayout(JsonObject receipt) {
        if (!deliveryPending(receipt)) return TxResult.fail("delivery_completed", "Cannot confiscate completed beneficiary funds; reconcile delivery explicitly");
        UUID coin = UUID.fromString(receipt.get("noteId").getAsString()), issuer = UUID.fromString(receipt.get("issuer").getAsString());
        BigDecimal quantity = Money.parse(receipt.get("privateAmount").getAsString());
        String account = receipt.get("account").getAsString();
        BigDecimal fromOrdinary = Money.parse(receipt.get("fromOrdinary").getAsString()), fromSettled = Money.parse(receipt.get("fromSettled").getAsString());
        if (fromOrdinary.signum() > 0) addOrdinarySystem(account, "note", fromOrdinary);
        if (fromSettled.signum() > 0) settledAssets.computeIfAbsent(account, unused -> new LinkedHashMap<>()).put("note", Money.of(systemSettlementBalance(account, "note").add(fromSettled)));
        if (quantity.signum() > 0) {
            setPersonalExact(coin, issuer, personalHoldingExact(coin, issuer).add(quantity));
            // Return only escrowed currency. Auto inventory derives from holdings; manual offers restore escrow quantity.
            if (!personalNoteById(coin).autoListing())
                setPersonalOffer(coin, issuer, personalOffered(coin, issuer).add(receipt.has("listingReserved")
                        ? new BigInteger(receipt.get("listingReserved").getAsString()) : quantity.toBigIntegerExact()));
        }
        receipt.addProperty("status", "reversed"); receipt.addProperty("pendingDelivery", false); receipt.addProperty("reversedAt", System.currentTimeMillis());
        return TxResult.ok(BigInteger.ZERO, "Uncompleted payout assets returned");
    }
    private BigDecimal pendingIssuerNotes() {
        BigDecimal amount = Money.ZERO;
        for (JsonObject receipt : clearingReceipts.values()) if (deliveryPending(receipt) && receipt.has("kind") && "issuer-offer-payout".equals(receipt.get("kind").getAsString()))
            amount = amount.add(Money.parse(receipt.get("centralBudget").getAsString()));
        return Money.of(amount);
    }

    private boolean deliveryPending(JsonObject receipt) {
        return "settled".equals(receipt.get("status").getAsString()) && receipt.has("pendingDelivery") && receipt.get("pendingDelivery").getAsBoolean();
    }
    public boolean noteHasPending(UUID id) {
        if (id == null) return false;
        for (JsonObject receipt : clearingReceipts.values()) {
            if (!deliveryPending(receipt)) continue;
            for (JsonElement row : receipt.getAsJsonArray("legs")) {
                if (id.toString().equals(row.getAsJsonObject().get("noteId").getAsString())) return true;
            }
        }
        return false;
    }
    /** Real assets less unsettled external-delivery receipts. Includes ordinary shop balances. */
    public BigDecimal systemAvailableBalance(String account, String currency) {
        BigDecimal total = systemSettlementBalance(account, currency).add(ordinarySystemBalance(account, currency));
        for (JsonObject receipt : clearingReceipts.values()) {
            if (!deliveryPending(receipt) || !account.equals(receipt.get("account").getAsString())
                    || !currency.equals(receipt.get("currency").getAsString())) continue;
            boolean incoming = !receipt.has("kind") || "land-direct".equals(receipt.get("kind").getAsString()) || "shop-direct".equals(receipt.get("kind").getAsString()) && receipt.get("buying").getAsBoolean();
            if (incoming) total = total.subtract(Money.parse(receipt.get("amount").getAsString()));
        }
        return Money.of(total.max(BigDecimal.ZERO));
    }
    public BigDecimal personalRecoveredAvailable(UUID id) {
        BigDecimal total = personalRecovered(id);
        for (JsonObject receipt : clearingReceipts.values()) {
            if (!deliveryPending(receipt) || receipt.has("kind")) continue;
            for (JsonElement row : receipt.getAsJsonArray("legs")) {
                JsonObject leg = row.getAsJsonObject();
                if (id.toString().equals(leg.get("noteId").getAsString())) total = total.subtract(Money.parse(leg.get("notes").getAsString()));
            }
        }
        return Money.of(total.max(BigDecimal.ZERO));
    }
    /** Call only after durable external delivery proof. Pending/uncertain orders remain locked. */
    public TxResult completeSystemPayment(String reference) {
        JsonObject receipt = clearingReceipts.get(reference);
        if (receipt == null) return TxResult.fail("missing", "No payment receipt");
        if (!"settled".equals(receipt.get("status").getAsString())) return TxResult.fail("reversed", "Payment was reversed");
        if (!deliveryPending(receipt)) return TxResult.ok(BigInteger.ZERO, "Already complete");
        if (hasLandPurchase(reference)) {
            for (String suffix : java.util.List.of(":t", ":n")) if (clearingReceipts.containsKey(reference + suffix)) {
                TxResult done = completeSystemPayment(reference + suffix); if (!done.success()) return done;
            }
        }
        if (receipt.has("kind") && "issuer-offer-payout".equals(receipt.get("kind").getAsString())) return completeIssuerPayout(receipt);
        receipt.addProperty("pendingDelivery", false); receipt.addProperty("completedAt", System.currentTimeMillis());
        return TxResult.ok(BigInteger.ZERO, "Delivery completed");
    }

    /** Exact, non-spendable recovered notes. Total issued notes remain auditable separately. */
    public BigDecimal personalRecovered(UUID id) { return recoveredNotes.getOrDefault(id, Money.ZERO); }
    public BigDecimal personalActiveExact(PersonalNote note) {
        return note == null ? Money.ZERO : Money.of(new BigDecimal(note.circulation()).subtract(personalRecovered(note.id())));
    }
    public boolean personalReserveSwitching(UUID id) { return reserveSwitching.contains(id); }
    public BigDecimal effectiveBackingExact(PersonalNote note) {
        if (note == null) return Money.ZERO;
        BigInteger whole = "note".equals(note.anchor()) ? centralReserve(note.issuer()) : personalReserve(note.issuer(), note.anchor());
        return Money.of(new BigDecimal(whole).add(reserveDust.getOrDefault(note.id(), Money.ZERO)));
    }
    private void setBackingExact(PersonalNote note, BigDecimal amount) {
        amount = Money.of(amount);
        if (amount.signum() < 0) throw new IllegalArgumentException("negative reserve");
        BigInteger whole = Money.units(amount);
        if ("note".equals(note.anchor())) centralReserveAccounts.put(note.issuer(), whole);
        else personalReserves.computeIfAbsent(note.issuer(), unused -> new LinkedHashMap<>()).put(note.anchor(), whole);
        reserveDust.put(note.id(), amount.subtract(new BigDecimal(whole)));
    }
    private UUID nestedId(String anchor) {
        if (anchor == null || !anchor.startsWith("personal:")) return null;
        try { return UUID.fromString(anchor.substring(9)); } catch (IllegalArgumentException ignored) { return null; }
    }
    private boolean baseAnchor(String anchor) { return "diamond".equals(anchor) || "copper".equals(anchor) || "note".equals(anchor); }
    public record AnchorEligibility(boolean allowed, String code, String reason) {}
    /** Read-only prospective edge check. Shared collateral branches are valid; a path back to this note is not. */
    public AnchorEligibility personalReserveEligibility(UUID noteId, String anchor) {
        PersonalNote root = personalNoteById(noteId);
        if (root == null || root.bankrupt()) return new AnchorEligibility(false, "missing_or_closed", "当前货币不存在或已关闭");
        if (noteHasPending(noteId)) return new AnchorEligibility(false, "delivery_pending", "当前货币有待完成交易");
        return anchorEligibility(root, anchor);
    }
    private AnchorEligibility anchorEligibility(PersonalNote root, String anchor) {
        java.util.Set<UUID> visited = new java.util.HashSet<>();
        visited.add(root.id());
        for (int depth = 0; depth < 32; depth++) {
            if (baseAnchor(anchor)) return new AnchorEligibility(true, "ok", "可用作准备金");
            UUID id = nestedId(anchor);
            if (id == null) return new AnchorEligibility(false, "unknown_anchor", "无法识别准备金币种");
            if (!visited.add(id)) return new AnchorEligibility(false, "cycle", "候选货币的准备金链形成循环，不能选择");
            PersonalNote next = personalNoteById(id);
            if (next == null || next.bankrupt()) return new AnchorEligibility(false, "missing_or_closed", "准备金链含不存在或已关闭的货币");
            if (noteHasPending(id)) return new AnchorEligibility(false, "delivery_pending", "准备金链有待完成交易");
            anchor = next.anchor();
        }
        return new AnchorEligibility(false, "depth", "准备金链过深，不能选择");
    }
    private boolean usableAnchor(PersonalNote root, String anchor) {
        return anchorEligibility(root, anchor).allowed();
    }
    public record ClearingLeg(UUID noteId, String anchor, BigDecimal notes, BigDecimal reserve) {}
    public record ClearingQuote(boolean success, String code, String currency, BigDecimal amount, java.util.List<ClearingLeg> legs) {
        static ClearingQuote fail(String code) { return new ClearingQuote(false, code, "", Money.ZERO, java.util.List.of()); }
    }
    /** Read-only executable backing quote. Never uses the issuer target or speculative market flow. */
    public ClearingQuote quotePersonalClearing(UUID id, BigDecimal quantity) {
        return quotePersonalClearingInternal(id, quantity, false);
    }
    private ClearingQuote quotePersonalClearingInternal(UUID id, BigDecimal quantity, boolean valuationOnly) {
        if (quantity == null || quantity.signum() <= 0 || quantity.scale() > Money.SCALE) return ClearingQuote.fail("amount");
        java.util.List<ClearingLeg> legs = new java.util.ArrayList<>();
        java.util.Set<UUID> visited = new java.util.HashSet<>();
        BigDecimal amount = Money.of(quantity);
        for (int depth = 0; depth < 32; depth++) {
            PersonalNote note = personalNoteById(id);
            if (note == null || note.bankrupt()) return ClearingQuote.fail("missing_or_closed");
            if (!visited.add(id)) return ClearingQuote.fail("cycle");
            if (reserveSwitching.contains(id) || note.redemptionPaused()) return ClearingQuote.fail("paused");
            if (!valuationOnly && noteHasPending(id)) return ClearingQuote.fail("delivery_pending");
            BigDecimal active = personalActiveExact(note), reserve = effectiveBackingExact(note);
            if (active.signum() <= 0 || amount.compareTo(active) > 0 || reserve.signum() <= 0) return ClearingQuote.fail("short_reserve");
            BigDecimal out = amount.multiply(reserve).divide(active, Money.SCALE, RoundingMode.DOWN);
            if (out.signum() <= 0) return ClearingQuote.fail("below_precision");
            legs.add(new ClearingLeg(id, note.anchor(), amount, out));
            if (baseAnchor(note.anchor())) return new ClearingQuote(true, "ok", note.anchor(), out, java.util.List.copyOf(legs));
            id = nestedId(note.anchor());
            if (id == null) return ClearingQuote.fail("unknown_anchor");
            amount = out;
        }
        return ClearingQuote.fail("depth");
    }
    public Map<String, BigDecimal> systemSettlementAssets(String account) {
        return Map.copyOf(settledAssets.getOrDefault(account, Map.of()));
    }
    public boolean personalTradingAllowed(PersonalNote note) {
        if (note == null) return false;
        java.util.Set<UUID> visited = new java.util.HashSet<>();
        for (int depth = 0; depth < 32; depth++) {
            if (note.bankrupt() || reserveSwitching.contains(note.id()) || noteHasPending(note.id()) || !visited.add(note.id())) return false;
            if (baseAnchor(note.anchor())) return true;
            note = personalNoteById(nestedId(note.anchor()));
            if (note == null) return false;
        }
        return false;
    }
    public BigDecimal systemSettlementBalance(String account, String currency) {
        return settledAssets.getOrDefault(account, java.util.Map.of()).getOrDefault(currency, Money.ZERO);
    }
    /** Real successful ledger events, newest first. Persistence caller must durably save before replying. */
    public java.util.List<JsonObject> personalSettlements(UUID id, int limit) {
        java.util.List<JsonObject> events = new java.util.ArrayList<>();
        for (JsonObject event : clearingReceipts.values()) {
            for (JsonElement leg : event.getAsJsonArray("legs")) {
                if (id.toString().equals(leg.getAsJsonObject().get("noteId").getAsString())) { events.add(event.deepCopy()); break; }
            }
        }
        java.util.Collections.reverse(events);
        return java.util.List.copyOf(events.subList(0, Math.min(Math.max(0, Math.min(5, limit)), events.size())));
    }
    /** All validation before mutation. Single-thread server ledger operation; save/commodity delivery is caller's transaction. */
    public TxResult settlePersonalToSystem(UUID payer, UUID id, BigDecimal quantity, String account, String reference) {
        if (payer == null || id == null || quantity == null || account == null || account.isBlank()
                || account.length() > 80 || reference == null || reference.isBlank() || reference.length() > 160)
            return TxResult.fail("invalid", "Invalid settlement");
        String signature = payer + "|" + id + "|" + quantity.stripTrailingZeros().toPlainString() + "|" + account;
        JsonObject prior = clearingReceipts.get(reference);
        if (prior != null) return signature.equals(prior.get("signature").getAsString()) && "settled".equals(prior.get("status").getAsString())
                ? TxResult.ok(Money.units(quantity), "Already settled") : TxResult.fail("reference_conflict", "Reference belongs to another settlement");
        ClearingQuote quote = quotePersonalClearing(id, quantity);
        if (!quote.success()) return TxResult.fail(quote.code(), "Collateral chain cannot settle");
        BigDecimal held = personalHoldingExact(id, payer);
        if (held.subtract(new BigDecimal(personalLockedOffer(id, payer))).compareTo(quantity) < 0) return TxResult.fail("funds", "Not enough unlisted personal currency");
        // Quote traverses a single collateral chain, so each reserve is touched exactly once.
        setPersonalExact(id, payer, held.subtract(quantity));
        for (ClearingLeg leg : quote.legs()) {
            PersonalNote note = personalNoteById(leg.noteId());
            setBackingExact(note, effectiveBackingExact(note).subtract(leg.reserve()));
            recoveredNotes.put(note.id(), Money.of(personalRecovered(note.id()).add(leg.notes())));
        }
        java.util.Map<String, BigDecimal> target = settledAssets.computeIfAbsent(account, unused -> new LinkedHashMap<>());
        target.put(quote.currency(), Money.of(target.getOrDefault(quote.currency(), Money.ZERO).add(quote.amount())));
        // Commodity custody already contains the deposited collateral. Do NOT add it again.
        // Central reserves were removed from public liability when funded; restore only the real released amount.
        if ("note".equals(quote.currency())) supply("note").addLiability(quote.amount());
        JsonObject receipt = new JsonObject();
        receipt.addProperty("reference", reference); receipt.addProperty("signature", signature);
        receipt.addProperty("timestamp", System.currentTimeMillis()); receipt.addProperty("account", account);
        receipt.addProperty("payer", payer.toString()); receipt.addProperty("currency", quote.currency());
        receipt.addProperty("amount", Money.stored(quote.amount())); receipt.addProperty("status", "settled"); receipt.addProperty("pendingDelivery", true);
        JsonArray entries = new JsonArray();
        for (ClearingLeg leg : quote.legs()) {
            JsonObject row = new JsonObject(); row.addProperty("noteId", leg.noteId().toString());
            row.addProperty("anchor", leg.anchor()); row.addProperty("notes", Money.stored(leg.notes()));
            row.addProperty("reserve", Money.stored(leg.reserve())); entries.add(row);
        }
        receipt.add("legs", entries); clearingReceipts.put(reference, receipt);
        return TxResult.ok(Money.units(quantity), "Collateral settlement posted");
    }
    /** Exact deposit of already-owned savings/notes; cannot fund from an appraisal or another free asset. */
    public TxResult depositPersonalReserveExact(UUID issuer, BigDecimal quantity) {
        PersonalNote note = personalNote(issuer);
        if (note == null || note.bankrupt() || noteHasPending(note.id()) || quantity == null || quantity.signum() <= 0 || quantity.scale() > Money.SCALE)
            return TxResult.fail("invalid", "Invalid reserve deposit");
        if (!usableAnchor(note, note.anchor())) return TxResult.fail("anchor", "Invalid collateral chain");
        BigDecimal amount = Money.of(quantity); UUID nested = nestedId(note.anchor());
        BigDecimal held = nested == null ? balanceExact(issuer, note.anchor()) : personalHoldingExact(nested, issuer);
        if (held.subtract(nested == null ? BigDecimal.ZERO : new BigDecimal(personalLockedOffer(nested, issuer))).compareTo(amount) < 0) return TxResult.fail("funds", "Not enough unlisted designated savings");
        if (nested == null) debitBalance(issuer, note.anchor(), amount);
        else setPersonalExact(nested, issuer, held.subtract(amount));
        if ("note".equals(note.anchor())) supply("note").addLiability(amount.negate());
        setBackingExact(note, effectiveBackingExact(note).add(amount));
        reserveSwitching.remove(note.id());
        return TxResult.ok(Money.units(amount), "Reserve funded");
    }
    /** Return collateral to its owner and pause this note until a new actual deposit arrives. */
    public TxResult switchPersonalReserve(UUID issuer, String anchor) {
        PersonalNote note = personalNote(issuer);
        if (note == null) return TxResult.fail("missing_or_closed", "当前货币不存在");
        AnchorEligibility eligibility = personalReserveEligibility(note.id(), anchor);
        if (!eligibility.allowed()) return TxResult.fail(eligibility.code(), eligibility.reason());
        BigDecimal reserve = effectiveBackingExact(note); String old = note.anchor(); UUID nested = nestedId(old);
        FractionChange change = quoteFractionChange(old, reserve);
        if (nested == null) creditBalance(issuer, old, reserve);
        else setPersonalExact(nested, issuer, personalHoldingExact(nested, issuer).add(reserve));
        if ("note".equals(old)) supply("note").addLiability(reserve);
        setBackingExact(note, Money.ZERO);
        note.adopt(note.regime(), anchor, null); note.setLocked(BigInteger.ZERO);
        reserveDust.put(note.id(), Money.ZERO); reserveSwitching.add(note.id());
        String changeMessage = settleFractionChange(issuer, change);
        return TxResult.ok(Money.units(reserve), "Reserve returned; pricing and settlement paused until funded" + changeMessage);
    }
    /** New reverse trade, not a refund: use current real backing ratios; no rate when active supply is zero. */
    public ClearingQuote quotePersonalFromSystem(UUID id, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0 || quantity.scale() > Money.SCALE) return ClearingQuote.fail("amount");
        java.util.List<ClearingLeg> legs = new java.util.ArrayList<>(); java.util.Set<UUID> visited = new java.util.HashSet<>();
        BigDecimal amount = Money.of(quantity);
        for (int depth = 0; depth < 32; depth++) {
            PersonalNote note = personalNoteById(id);
            if (note == null || note.bankrupt()) return ClearingQuote.fail("missing_or_closed");
            if (!visited.add(id)) return ClearingQuote.fail("cycle");
            if (reserveSwitching.contains(id) || note.redemptionPaused()) return ClearingQuote.fail("paused");
            if (noteHasPending(id)) return ClearingQuote.fail("delivery_pending");
            BigDecimal active = personalActiveExact(note), reserve = effectiveBackingExact(note);
            if (active.signum() <= 0 || reserve.signum() <= 0) return ClearingQuote.fail("no_current_rate");
            if (personalRecovered(id).compareTo(amount) < 0) return ClearingQuote.fail("short_recovery");
            BigDecimal required = amount.multiply(reserve).divide(active, Money.SCALE, RoundingMode.CEILING);
            if (required.signum() <= 0) return ClearingQuote.fail("below_precision");
            legs.add(new ClearingLeg(id, note.anchor(), amount, required));
            if (baseAnchor(note.anchor())) return new ClearingQuote(true, "ok", note.anchor(), required, java.util.List.copyOf(legs));
            id = nestedId(note.anchor()); if (id == null) return ClearingQuote.fail("unknown_anchor"); amount = required;
        }
        return ClearingQuote.fail("depth");
    }
    /** Maximum real payout at 0.0001 precision, bounded by every recovered leg and terminal assets. */
    public BigDecimal personalSystemCapacity(UUID id, String account) {
        if (id == null || account == null) return Money.ZERO;
        BigInteger low = BigInteger.ZERO, high = personalRecovered(id).movePointRight(Money.SCALE).toBigInteger();
        for (int attempts = 0; low.compareTo(high) < 0 && attempts < 256; attempts++) {
            BigInteger middle = low.add(high).add(BigInteger.ONE).shiftRight(1);
            ClearingQuote q = quotePersonalFromSystem(id, new BigDecimal(middle, Money.SCALE));
            BigDecimal available = q.success() ? systemAvailableBalance(account, q.currency()) : Money.ZERO;
            if (q.success() && available.compareTo(q.amount()) >= 0) low = middle; else high = middle.subtract(BigInteger.ONE);
        }
        return new BigDecimal(low, Money.SCALE);
    }

    public TxResult settlePersonalFromSystem(UUID payee, UUID id, BigDecimal quantity, String account, String reference) {
        if (payee == null || id == null || quantity == null || account == null || account.isBlank() || account.length() > 80
                || reference == null || reference.isBlank() || reference.length() > 160) return TxResult.fail("invalid", "Invalid reverse settlement");
        String signature = "outbound|" + payee + "|" + id + "|" + quantity.stripTrailingZeros().toPlainString() + "|" + account;
        JsonObject previous = clearingReceipts.get(reference);
        if (previous != null) return signature.equals(previous.get("signature").getAsString()) && "settled".equals(previous.get("status").getAsString())
                ? TxResult.ok(Money.units(quantity), "Already settled") : TxResult.fail("reference_conflict", "Payment reference conflict");
        ClearingQuote quote = quotePersonalFromSystem(id, quantity);
        if (!quote.success()) return TxResult.fail(quote.code(), "Cannot fund reverse collateral chain");
        BigDecimal ordinary = "shop".equals(account) ? shopExact(quote.currency()) : Money.ZERO;
        BigDecimal settled = systemSettlementBalance(account, quote.currency());
        if (systemAvailableBalance(account, quote.currency()).compareTo(quote.amount()) < 0) return TxResult.fail("insufficient_system", "System has insufficient real terminal assets");
        BigDecimal fromSettled = settled.min(quote.amount()), fromOrdinary = quote.amount().subtract(fromSettled);
        if (fromSettled.signum() > 0) settledAssets.get(account).put(quote.currency(), Money.of(settled.subtract(fromSettled)));
        if (fromOrdinary.signum() > 0) addShop(quote.currency(), fromOrdinary.negate());
        for (ClearingLeg leg : quote.legs()) {
            PersonalNote note = personalNoteById(leg.noteId());
            setBackingExact(note, effectiveBackingExact(note).add(leg.reserve()));
            recoveredNotes.put(note.id(), Money.of(personalRecovered(note.id()).subtract(leg.notes())));
        }
        setPersonalExact(id, payee, personalHoldingExact(id, payee).add(quantity));
        if ("note".equals(quote.currency())) supply("note").addLiability(quote.amount().negate());
        JsonObject receipt = new JsonObject(); receipt.addProperty("kind", "personal-outbound");
        receipt.addProperty("reference", reference); receipt.addProperty("signature", signature); receipt.addProperty("payer", payee.toString());
        receipt.addProperty("account", account); receipt.addProperty("currency", quote.currency()); receipt.addProperty("amount", Money.stored(quote.amount()));
        receipt.addProperty("fromSettled", Money.stored(fromSettled)); receipt.addProperty("fromOrdinary", Money.stored(fromOrdinary));
        receipt.addProperty("timestamp", System.currentTimeMillis()); receipt.addProperty("status", "settled"); receipt.addProperty("pendingDelivery", true);
        JsonArray legs = new JsonArray();
        for (ClearingLeg leg : quote.legs()) {
            JsonObject row = new JsonObject(); row.addProperty("noteId", leg.noteId().toString()); row.addProperty("anchor", leg.anchor());
            row.addProperty("notes", Money.stored(leg.notes())); row.addProperty("reserve", Money.stored(leg.reserve())); legs.add(row);
        }
        receipt.add("legs", legs); clearingReceipts.put(reference, receipt);
        return TxResult.ok(Money.units(quantity), "Reverse collateral settlement posted");
    }
    private TxResult rollbackPersonalOutbound(JsonObject receipt) {
        JsonArray legs = receipt.getAsJsonArray("legs"); JsonObject first = legs.get(0).getAsJsonObject();
        UUID payee = UUID.fromString(receipt.get("payer").getAsString()), topId = UUID.fromString(first.get("noteId").getAsString());
        BigDecimal quantity = Money.parse(first.get("notes").getAsString());
        BigDecimal free = personalHoldingExact(topId, payee).subtract(new BigDecimal(personalLockedOffer(topId, payee)));
        if (free.compareTo(quantity) < 0) return TxResult.fail("spent", "Received currency is no longer unencumbered");
        for (JsonElement element : legs) {
            JsonObject row = element.getAsJsonObject(); PersonalNote note = personalNoteById(UUID.fromString(row.get("noteId").getAsString()));
            if (note == null || note.bankrupt() || reserveSwitching.contains(note.id()) || !note.anchor().equals(row.get("anchor").getAsString())
                    || effectiveBackingExact(note).compareTo(Money.parse(row.get("reserve").getAsString())) < 0)
                return TxResult.fail("changed", "Collateral changed; cannot automatically compensate");
        }
        setPersonalExact(topId, payee, personalHoldingExact(topId, payee).subtract(quantity));
        for (JsonElement element : legs) {
            JsonObject row = element.getAsJsonObject(); PersonalNote note = personalNoteById(UUID.fromString(row.get("noteId").getAsString()));
            setBackingExact(note, effectiveBackingExact(note).subtract(Money.parse(row.get("reserve").getAsString())));
            recoveredNotes.put(note.id(), Money.of(personalRecovered(note.id()).add(Money.parse(row.get("notes").getAsString()))));
        }
        String account = receipt.get("account").getAsString(), currency = receipt.get("currency").getAsString();
        BigDecimal fromSettled = Money.parse(receipt.get("fromSettled").getAsString()), fromOrdinary = Money.parse(receipt.get("fromOrdinary").getAsString());
        if (fromSettled.signum() > 0) settledAssets.computeIfAbsent(account, unused -> new LinkedHashMap<>()).put(currency, Money.of(systemSettlementBalance(account, currency).add(fromSettled)));
        if (fromOrdinary.signum() > 0) addShop(currency, fromOrdinary);
        if ("note".equals(currency)) supply("note").addLiability(fromSettled.add(fromOrdinary));
        receipt.addProperty("status", "reversed"); receipt.addProperty("reversedAt", System.currentTimeMillis());
        return TxResult.ok(Money.units(quantity), "Reverse trade compensated");
    }

    /** One idempotent shop payment. Incoming personal notes use recursive collateral settlement. */
    public TxResult transferShopReferenced(UUID player, String currency, BigDecimal amount, boolean buying, String reference) {
        if (player == null || currency == null || amount == null || amount.signum() <= 0 || amount.scale() > Money.SCALE
                || reference == null || reference.isBlank() || reference.length() > 160)
            return TxResult.fail("invalid", "Invalid shop payment");
        if (currency.startsWith("personal:") && buying) {
            UUID id = nestedId(currency);
            return id == null ? TxResult.fail("currency", "Invalid currency") : settlePersonalToSystem(player, id, amount, "shop", reference);
        }
        if (currency.startsWith("personal:") && !buying) {
            if (!clearingReceipts.containsKey(reference)) return TxResult.fail("mixed_quote_required", "Confirm the issuer-offer and central-note payout combination");
            UUID id = nestedId(currency);
            return id == null ? TxResult.fail("currency", "Invalid currency") : settlePersonalFromSystem(player, id, amount, "shop", reference);
        }
        String signature = "shop|" + player + "|" + currency + "|" + amount.stripTrailingZeros().toPlainString() + "|" + buying;
        JsonObject prior = clearingReceipts.get(reference);
        if (prior != null) return signature.equals(prior.get("signature").getAsString()) && "settled".equals(prior.get("status").getAsString())
                ? TxResult.ok(Money.units(amount), "Already settled") : TxResult.fail("reference_conflict", "Payment reference conflict");
        TxResult result = transferShopExactInternal(player, currency, amount, buying);
        if (!result.success()) return result;
        JsonObject row = new JsonObject(); row.addProperty("kind", "shop-direct"); row.addProperty("reference", reference);
        row.addProperty("signature", signature); row.addProperty("payer", player.toString()); row.addProperty("currency", currency);
        row.addProperty("amount", Money.stored(amount)); row.addProperty("buying", buying); row.addProperty("account", "shop");
        row.addProperty("timestamp", System.currentTimeMillis()); row.addProperty("status", "settled"); row.addProperty("pendingDelivery", true); row.add("legs", new JsonArray());
        clearingReceipts.put(reference, row); return result;
    }
    private TxResult transferShopExactInternal(UUID player, String currency, BigDecimal amount, boolean buying) {
        if (currency.startsWith("personal:")) return transferShopPersonal(player, currency.substring(9), amount, buying);
        if (!baseAnchor(currency)) return TxResult.fail("currency", "Unsupported settlement currency");
        if (buying) {
            if (balanceExact(player, currency).compareTo(amount) < 0) return TxResult.fail("funds", "Not enough savings");
            debitBalance(player, currency, amount); addShop(currency, amount);
        } else {
            BigDecimal ordinary = shopExact(currency), settled = systemSettlementBalance("shop", currency);
            if (systemAvailableBalance("shop", currency).compareTo(amount) < 0) return TxResult.fail("insufficient_shop", "Shop has no real funds");
            BigDecimal fromOrdinary = ordinary.min(amount), fromSettlement = amount.subtract(fromOrdinary);
            addShop(currency, fromOrdinary.negate());
            if (fromSettlement.signum() > 0) settledAssets.get("shop").put(currency, Money.of(settled.subtract(fromSettlement)));
            creditBalance(player, currency, amount);
        }
        return TxResult.ok(Money.units(amount), buying ? "Shop purchase" : "Shop sale");
    }
    public String settlementStatus(String reference) {
        JsonObject row = clearingReceipts.get(reference);
        return row == null ? "none" : "reversed".equals(row.get("status").getAsString()) ? "rolled_back" : "applied";
    }
    public TxResult rollbackShopPayment(String reference) {
        JsonObject row = clearingReceipts.get(reference);
        if (row == null) return TxResult.fail("missing", "No payment");
        if ("reversed".equals(row.get("status").getAsString())) return TxResult.ok(BigInteger.ZERO, "Already rolled back");
        if (row.has("kind") && "issuer-offer-payout".equals(row.get("kind").getAsString())) return rollbackIssuerPayout(row);
        if (row.has("kind") && "personal-outbound".equals(row.get("kind").getAsString())) return rollbackPersonalOutbound(row);
        if (!row.has("kind") || !"shop-direct".equals(row.get("kind").getAsString())) return reversePersonalSettlement(reference);
        boolean wasPending = deliveryPending(row);
        row.addProperty("pendingDelivery", false);
        TxResult result = transferShopExactInternal(UUID.fromString(row.get("payer").getAsString()), row.get("currency").getAsString(),
                Money.parse(row.get("amount").getAsString()), !row.get("buying").getAsBoolean());
        if (!result.success()) row.addProperty("pendingDelivery", wasPending);
        if (result.success()) { row.addProperty("status", "reversed"); row.addProperty("reversedAt", System.currentTimeMillis()); }
        return result;
    }

    /** Redeems into real terminal-currency savings, never both account credit and physical item payout. */
    public TxResult redeemPersonalToSavings(UUID player, UUID id, BigDecimal amount, String reference) {
        if (player == null || reference == null || amount == null) return TxResult.fail("invalid", "Invalid redemption");
        String account = "savings:" + player;
        JsonObject existing = clearingReceipts.get(reference);
        if (existing != null) {
            String signature = player + "|" + id + "|" + amount.stripTrailingZeros().toPlainString() + "|" + account;
            return signature.equals(existing.get("signature").getAsString()) && "settled".equals(existing.get("status").getAsString())
                    ? TxResult.ok(Money.units(amount), "Already redeemed") : TxResult.fail("reference_conflict", "Redemption reference conflict");
        }
        ClearingQuote payout = quotePersonalClearing(id, amount);
        FractionChange change = payout.success() ? quoteFractionChange(payout.currency(), payout.amount()) : null;
        TxResult result = settlePersonalToSystem(player, id, amount, account, reference);
        if (!result.success()) return result;
        JsonObject receipt = clearingReceipts.get(reference);
        String currency = receipt.get("currency").getAsString(); BigDecimal delivered = Money.parse(receipt.get("amount").getAsString());
        settledAssets.get(account).put(currency, Money.of(systemSettlementBalance(account, currency).subtract(delivered)));
        creditBalance(player, currency, delivered);
        String changeMessage = settleFractionChange(player, change);
        receipt.addProperty("changeSettlement", changeMessage);
        receipt.addProperty("delivery", "savings"); receipt.addProperty("pendingDelivery", false);
        return TxResult.ok(result.amount(), result.message() + changeMessage);
    }

    public JsonObject personalSettlementReceipt(String reference) {
        JsonObject row = clearingReceipts.get(reference); return row == null ? null : row.deepCopy();
    }
    /** Player-to-player market payment moves real holdings, not collateral or recovery balances. */
    public TxResult transferPersonalExact(UUID from, UUID to, String token, BigDecimal amount) {
        UUID id = nestedId(token); PersonalNote note = personalNoteById(id);
        if (from == null || to == null || from.equals(to) || !personalTradingAllowed(note)
                || amount == null || amount.signum() <= 0 || amount.scale() > Money.SCALE)
            return TxResult.fail("invalid", "Invalid or paused personal transfer");
        BigDecimal held = personalHoldingExact(id, from);
        BigDecimal free = held.subtract(new BigDecimal(personalLockedOffer(id, from)));
        if (free.compareTo(amount) < 0) return TxResult.fail("funds", "Not enough unlisted personal currency");
        setPersonalExact(id, from, held.subtract(amount));
        setPersonalExact(id, to, personalHoldingExact(id, to).add(amount));
        return TxResult.ok(Money.units(amount), "Personal currency transferred");
    }
    /** Opt-in configured policy. Existing saves without a policy never auto-move assets. */
    public TxResult rebalancePersonalPolicy(UUID issuer) {
        PersonalNote note = personalNote(issuer);
        if (!personalTradingAllowed(note) || note.regime() == NoteRegime.FLOAT || !policies.containsKey(note.id()))
            return TxResult.fail("no_change", "No active automatic policy");
        PersonalPolicy policy = personalPolicy(note.id());
        BigDecimal active = personalActiveExact(note), reserve = effectiveBackingExact(note);
        BigDecimal actual = active.signum() == 0 ? policy.upper().add(BigDecimal.ONE) : reserve.divide(active, FxMath.MC);
        BigDecimal low = note.regime() == NoteRegime.HARD ? policy.target() : policy.lower();
        BigDecimal high = note.regime() == NoteRegime.HARD ? policy.target() : policy.upper();
        boolean changed = false;
        if (actual.compareTo(low) < 0) {
            BigDecimal missing = active.multiply(low).subtract(reserve).max(BigDecimal.ZERO).setScale(Money.SCALE, RoundingMode.CEILING);
            UUID nested = nestedId(note.anchor());
            BigDecimal available = nested == null ? balanceExact(issuer, note.anchor()) : personalHoldingExact(nested, issuer).subtract(new BigDecimal(personalLockedOffer(nested, issuer))).max(BigDecimal.ZERO);
            BigDecimal fund = Money.of(missing.min(available));
            if (fund.signum() > 0) {
                TxResult funded = depositPersonalReserveExact(issuer, fund);
                if (!funded.success()) return funded;
                reserve = effectiveBackingExact(note); changed = true;
            }
            BigDecimal supported = reserve.divide(low, Money.SCALE, RoundingMode.DOWN);
            BigDecimal withdraw = Money.of(active.subtract(supported).max(BigDecimal.ZERO).min(personalHoldingExact(note.id(), issuer).subtract(new BigDecimal(personalLockedOffer(note.id(), issuer))).max(BigDecimal.ZERO)));
            // Only issuer-owned spendable notes, never notes belonging to public holders.
            if (withdraw.signum() > 0) {
                setPersonalExact(note.id(), issuer, personalHoldingExact(note.id(), issuer).subtract(withdraw));
                recoveredNotes.put(note.id(), Money.of(personalRecovered(note.id()).add(withdraw))); changed = true;
            }
        } else if (actual.compareTo(high) > 0 && reserve.signum() > 0) {
            BigDecimal extra = reserve.divide(high, Money.SCALE, RoundingMode.DOWN).subtract(active).max(BigDecimal.ZERO);
            BigDecimal recycle = Money.of(extra.min(personalRecovered(note.id())));
            if (recycle.signum() > 0) {
                recoveredNotes.put(note.id(), Money.of(personalRecovered(note.id()).subtract(recycle)));
                setPersonalExact(note.id(), issuer, personalHoldingExact(note.id(), issuer).add(recycle));
                extra = extra.subtract(recycle); changed = true;
            }
            // New paper-denominated issuance remains whole units; residual backing stays in reserve.
            BigInteger fresh = extra.setScale(0, RoundingMode.DOWN).toBigInteger();
            if (fresh.signum() > 0) {
                note.setCirculation(note.circulation().add(fresh)); addHolding(note.id(), issuer, fresh); changed = true;
            }
        }
        return changed ? TxResult.ok(BigInteger.ZERO, "Designated reserve policy adjusted") : TxResult.fail("no_change", "Within policy range or no owned funds");
    }

    public record PersonalPolicy(BigDecimal target, BigDecimal lower, BigDecimal upper) {}
    public PersonalPolicy personalPolicy(UUID id) {
        PersonalNote note = personalNoteById(id);
        return policies.getOrDefault(id, new PersonalPolicy(note == null ? BigDecimal.ONE : new BigDecimal(note.par()), note == null ? BigDecimal.ONE : new BigDecimal(note.par()), note == null ? BigDecimal.ONE : new BigDecimal(note.par())));
    }
    public TxResult setPersonalPolicy(UUID issuer, NoteRegime regime, BigDecimal target, BigDecimal lower, BigDecimal upper) {
        PersonalNote note = personalNote(issuer);
        if (note == null || note.bankrupt() || noteHasPending(note.id()) || regime == null || target == null || target.signum() <= 0
                || lower == null || upper == null || lower.signum() <= 0 || lower.compareTo(target) > 0 || target.compareTo(upper) > 0)
            return TxResult.fail("policy", "Expected positive lower <= target <= upper");
        policies.put(note.id(), new PersonalPolicy(target, lower, upper)); note.adopt(regime, note.anchor(), null);
        return TxResult.ok(BigInteger.ZERO, "Policy target saved; actual backing is unchanged");
    }
    /** Only recovered non-spendable notes return to issuer. No public holder balance is touched. */
    public TxResult returnRecoveredPersonal(UUID issuer, BigDecimal quantity) {
        PersonalNote note = personalNote(issuer);
        if (note == null || note.bankrupt() || noteHasPending(note.id()) || reserveSwitching.contains(note.id()) || quantity == null || quantity.signum() <= 0
                || quantity.scale() > Money.SCALE || personalRecovered(note.id()).compareTo(quantity) < 0)
            return TxResult.fail("recovery", "Not enough recovered currency or reserve switch pending");
        BigDecimal amount = Money.of(quantity);
        if (note.regime() != NoteRegime.FLOAT) {
            PersonalPolicy policy = personalPolicy(note.id());
            BigDecimal rate = policy.target();
            if (note.regime() == NoteRegime.PEG && personalActiveExact(note).signum() > 0) {
                rate = effectiveBackingExact(note).divide(personalActiveExact(note), FxMath.MC).max(policy.lower()).min(policy.upper());
            }
            BigDecimal desired = personalActiveExact(note).add(amount).multiply(rate).setScale(Money.SCALE, RoundingMode.CEILING);
            BigDecimal missing = desired.subtract(effectiveBackingExact(note)).max(BigDecimal.ZERO);
            UUID nested = nestedId(note.anchor());
            BigDecimal held = nested == null ? balanceExact(issuer, note.anchor()) : personalHoldingExact(nested, issuer).subtract(new BigDecimal(personalLockedOffer(nested, issuer))).max(BigDecimal.ZERO);
            BigDecimal fund = Money.of(missing.min(held));
            if (fund.signum() > 0) { TxResult result = depositPersonalReserveExact(issuer, fund); if (!result.success()) return result; }
        }
        recoveredNotes.put(note.id(), Money.of(personalRecovered(note.id()).subtract(amount)));
        setPersonalExact(note.id(), issuer, personalHoldingExact(note.id(), issuer).add(amount));
        return TxResult.ok(Money.units(amount), "Recovered currency returned; actual support depends on available reserves");
    }

    /** Compensates exactly one unsuccessful external delivery, never restores a whole stale ledger. */
    public TxResult reversePersonalSettlement(String reference) {
        JsonObject receipt = clearingReceipts.get(reference);
        if (receipt == null) return TxResult.fail("missing", "No such settlement");
        if (receipt.has("kind")) return TxResult.fail("kind", "Use rollbackShopPayment for direct payments");
        if ("reversed".equals(receipt.get("status").getAsString())) return TxResult.ok(BigInteger.ZERO, "Already reversed");
        String account = receipt.get("account").getAsString(), currency = receipt.get("currency").getAsString();
        BigDecimal terminal = Money.parse(receipt.get("amount").getAsString());
        if (systemSettlementBalance(account, currency).compareTo(terminal) < 0)
            return TxResult.fail("spent", "Terminal assets are no longer available");
        JsonArray legs = receipt.getAsJsonArray("legs");
        for (JsonElement element : legs) {
            JsonObject leg = element.getAsJsonObject(); PersonalNote note = personalNoteById(UUID.fromString(leg.get("noteId").getAsString()));
            if (note == null || note.bankrupt() || reserveSwitching.contains(note.id())
                    || !note.anchor().equals(leg.get("anchor").getAsString())
                    || personalRecovered(note.id()).compareTo(Money.parse(leg.get("notes").getAsString())) < 0)
                return TxResult.fail("changed", "A settlement leg has changed; manual reconciliation required");
        }
        for (JsonElement element : legs) {
            JsonObject leg = element.getAsJsonObject(); PersonalNote note = personalNoteById(UUID.fromString(leg.get("noteId").getAsString()));
            setBackingExact(note, effectiveBackingExact(note).add(Money.parse(leg.get("reserve").getAsString())));
            recoveredNotes.put(note.id(), Money.of(personalRecovered(note.id()).subtract(Money.parse(leg.get("notes").getAsString()))));
        }
        JsonObject first = legs.get(0).getAsJsonObject(); UUID id = UUID.fromString(first.get("noteId").getAsString()), payer = UUID.fromString(receipt.get("payer").getAsString());
        setPersonalExact(id, payer, personalHoldingExact(id, payer).add(Money.parse(first.get("notes").getAsString())));
        settledAssets.get(account).put(currency, Money.of(systemSettlementBalance(account, currency).subtract(terminal)));
        if ("note".equals(currency)) supply("note").addLiability(terminal.negate());
        receipt.addProperty("status", "reversed"); receipt.addProperty("reversedAt", System.currentTimeMillis());
        return TxResult.ok(BigInteger.ZERO, "Settlement reversed");
    }
    public BigDecimal personalBackingDiamondPrice(PersonalNote note) {
        if (note == null) return BigDecimal.ZERO;
        BigDecimal active = personalActiveExact(note);
        if (active.signum() <= 0) return BigDecimal.ZERO;
        ClearingQuote quote = quotePersonalClearingInternal(note.id(), active, true);
        if (!quote.success()) return BigDecimal.ZERO;
        BigDecimal diamonds = switch (quote.currency()) {
            case "diamond" -> quote.amount();
            case "copper" -> { BigDecimal rate = unitsPer("copper", "diamond"); yield rate == null || rate.signum() <= 0 ? BigDecimal.ZERO : quote.amount().divide(rate, FxMath.MC); }
            case "note" -> { BigDecimal rate = unitsPer("note", "diamond"); yield rate == null || rate.signum() <= 0 ? BigDecimal.ZERO : quote.amount().divide(rate, FxMath.MC); }
            default -> BigDecimal.ZERO;
        };
        return diamonds.divide(active, FxMath.MC);
    }

    /** Real collateral value per active coin, directly in central notes when already note-backed. */
    public BigDecimal personalCentralPrice(PersonalNote note) {
        if (note == null) return BigDecimal.ZERO;
        BigDecimal active = personalActiveExact(note);
        if (active.signum() <= 0) return BigDecimal.ZERO;
        ClearingQuote quote = quotePersonalClearingInternal(note.id(), active, true);
        if (!quote.success()) return BigDecimal.ZERO;
        if ("note".equals(quote.currency())) return quote.amount().divide(active, FxMath.MC);
        BigDecimal rate = unitsPer("note", quote.currency());
        return rate == null || rate.signum() <= 0 ? BigDecimal.ZERO
                : quote.amount().multiply(rate, FxMath.MC).divide(active, FxMath.MC);
    }

    public BigDecimal personalDiamondPrice(PersonalNote note) {
        return personalBackingDiamondPrice(note);
    }

    /** Peg and hard quotes ignore buy/sell flow and do not fall when reserves are short. */
    private BigDecimal fixedDiamondQuote(PersonalNote note) {
        return switch (note.reserveCurrency()) {
            case "copper" -> {
                BigInteger rate = copperPerDiamond();
                if (rate == null || rate.signum() <= 0) {
                    yield BigDecimal.ZERO;
                }
                yield new BigDecimal(note.par()).divide(new BigDecimal(rate), FxMath.MC);
            }
            case "note" -> {
                BigDecimal notesPerDiamond = unitsPer("note", "diamond");
                if (notesPerDiamond == null || notesPerDiamond.signum() <= 0) {
                    yield BigDecimal.ZERO;
                }
                yield new BigDecimal(note.par()).divide(notesPerDiamond, FxMath.MC);
            }
            default -> new BigDecimal(note.par());
        };
    }

    /**
     * Deposited issuance reserve that prices and redeems this note.
     * Copper and diamond use {@code personalReserves}. A 央行券 anchor uses the issuer's
     * 央行券 reserve account. The displayed lock is not subtracted.
     */
    public BigInteger effectiveBacking(PersonalNote note) {
        if (note == null) {
            return BigInteger.ZERO;
        }
        if ("note".equals(note.reserveCurrency())) {
            return centralReserve(note.issuer());
        }
        BigInteger held = personalReserve(note.issuer(), note.reserveCurrency());
        return held.signum() < 0 ? BigInteger.ZERO : held;
    }

    public NoteTrade createPersonalNote(UUID issuer, String name, boolean copperPar, BigInteger par) {
        return createPersonalNote(issuer, name, NoteRegime.FLOAT, copperPar ? "copper" : "diamond", par);
    }

    public NoteTrade createPersonalNote(UUID issuer, String name, NoteRegime regime, String anchor, BigInteger par) {
        if (!PersonalMarket.validName(name)) {
            return NoteTrade.fail("name");
        }
        if (systemCurrencyName(name)) return NoteTrade.fail("reserved_name");
        if (personalNote(issuer) != null) {
            return NoteTrade.fail("exists");
        }
        if (personalNoteByName(name) != null) {
            return NoteTrade.fail("taken");
        }
        if (par == null || par.signum() <= 0) {
            return NoteTrade.fail("par");
        }
        if (!validAnchor(regime, anchor) || !("diamond".equals(anchor) || "copper".equals(anchor))) {
            return NoteTrade.fail("initial_physical_reserve");
        }
        PersonalNote note = new PersonalNote(UUID.randomUUID(), issuer, name, regime, anchor, par);
        if (!usableAnchor(note, anchor)) return NoteTrade.fail("anchor");
        personalNotes.put(note.id(), note);
        return NoteTrade.ok(note, BigInteger.ZERO, BigInteger.ZERO);
    }

    private boolean systemCurrencyName(String name) {
        String key = PersonalMarket.canonicalName(name);
        for (CurrencyDef currency : currencies.values()) {
            if (key.equals(PersonalMarket.canonicalName(currency.id()))
                    || key.equals(PersonalMarket.canonicalName(currency.name()))) return true;
        }
        return false;
    }
    /** Display name only: all balances, offers, collateral and receipts retain their UUID identity. */
    public TxResult renamePersonalNote(UUID actor, UUID noteId, String name, boolean operator) {
        PersonalNote note = personalNoteById(noteId);
        if (note == null) return TxResult.fail("none", "没有这张个人货币");
        if (actor == null || (!operator && !actor.equals(note.issuer()))) return TxResult.fail("permission", "仅发行人或管理员可以改名");
        if (!PersonalMarket.validName(name)) return TxResult.fail("name", "名称须为一至三个汉字或英文字母");
        if (systemCurrencyName(name)) return TxResult.fail("reserved_name", "不能使用系统货币的名称");
        PersonalNote other = personalNoteByName(name);
        if (other != null && !other.id().equals(noteId)) return TxResult.fail("taken", "此名称已被使用");
        note.rename(name);
        return TxResult.ok(BigInteger.ZERO, "个人货币已改名");
    }

    /** Every other regime, and every valid peg anchor, without posting a switch. */
    public List<RegimePreview> regimePreviews(PersonalNote note) {
        List<RegimePreview> options = new ArrayList<>();
        if (note == null) {
            return options;
        }
        for (NoteRegime regime : NoteRegime.values()) {
            for (String anchor : anchorsOf(regime)) {
                if (regime == note.regime() && anchor.equals(note.anchor())) {
                    continue;
                }
                options.add(describeSwitch(note, regime, anchor, false));
            }
        }
        return options;
    }

    /**
     * Posts a regime change when the target rule's collateral is already in place.
     * Circulation, locked collateral, and personal reserves are not reduced, and no notes are issued.
     */
    public RegimePreview switchPersonalRegime(UUID issuer, NoteRegime regime, String anchor) {
        PersonalNote note = personalNote(issuer);
        if (note == null) {
            return new RegimePreview(regime, anchor, false, false, "没有这张个人币。");
        }
        if (anchor == null || anchor.isBlank()) {
            anchor = note.anchor();
        }
        RegimePreview preview = describeSwitch(note, regime, anchor, true);
        if (!preview.allowed()) {
            return preview;
        }
        BigDecimal opening = regime == NoteRegime.PEG
                ? personalDiamondPrice(note)
                : regime == NoteRegime.FLOAT ? officialOrFace(note) : null;
        note.adopt(regime, anchor, opening);
        if (regime == NoteRegime.FLOAT) {
            note.setBuys(BigInteger.ZERO);
            note.setSells(BigInteger.ZERO);
        }
        return new RegimePreview(regime, anchor, true, true, successSentence(note, opening));
    }

    private RegimePreview describeSwitch(PersonalNote note, NoteRegime regime, String anchor, boolean posting) {
        String name = note.name();
        if (note.bankrupt() || reserveSwitching.contains(note.id()) || noteHasPending(note.id())) {
            return refused(regime, anchor, "「" + name + "」已经破产，不能改汇率制度。");
        }
        if (regime == null || !validAnchor(regime, anchor)) {
            return refused(regime, anchor, "请指定 float、peg 或 hard。计价货币用 diamond、copper 或 note。");
        }
        AnchorEligibility eligibility = personalReserveEligibility(note.id(), anchor);
        if (!eligibility.allowed()) return refused(regime, anchor, eligibility.reason());
        if (suspended(note)) {
            return refused(regime, anchor, "「" + name + "」的兑付已经暂停，不能改汇率制度。");
        }
        if (regime == note.regime() && anchor.equals(note.anchor())) {
            return refused(regime, anchor, "「" + name + "」已经实行" + regimeZh(regime) + "，计价货币是"
                    + anchorZh(anchor) + "，无需再切换。");
        }
        List<String> gaps = new ArrayList<>();
        if (!anchor.equals(note.anchor()) && note.locked().signum() > 0) {
            gaps.add("「" + name + "」已锁定" + anchorZh(note.anchor()) + " ×"
                    + CompactFormat.format(note.locked()) + "。已锁定的抵押不能改记到"
                    + anchorZh(anchor) + "上，也不能在切换时解除");
        }
        BigInteger face = note.par();
        BigInteger personalNeed = personalNeed(regime, anchor, face, note.circulation());
        BigInteger centralNeed = centralNeed(regime, anchor, face, note.circulation());
        BigInteger haveLock = anchor.equals(note.anchor()) ? note.locked() : BigInteger.ZERO;
        BigInteger haveReserve = personalReserve(note.issuer(), anchor);
        BigInteger haveCentral = centralReserve(note.issuer());
        if (regime == NoteRegime.HARD) {
            BigDecimal live = personalDiamondPrice(note);
            BigDecimal faceDiamonds = faceDiamonds(anchor, face);
            if (note.circulation().signum() > 0 && live.subtract(faceDiamonds).abs().compareTo(new BigDecimal("0.0000005")) > 0) {
                gaps.add("现价每张 " + CompactFormat.formatRate(live) + " 钻石，面额每张 "
                        + CompactFormat.formatRate(faceDiamonds) + " 钻石，两者不一致。固定汇率制要求现价与面额相同");
            }
            BigInteger faceCover = face.multiply(note.circulation());
            if (!"note".equals(anchor) && haveLock.compareTo(faceCover) < 0) {
                gaps.add("已锁定的" + anchorZh(anchor) + " ×" + CompactFormat.format(haveLock)
                        + " 不足以按面额兑付全部流通 ×" + CompactFormat.format(note.circulation())
                        + "，尚缺 ×" + CompactFormat.format(faceCover.subtract(haveLock)));
            }
        }
        if (regime != NoteRegime.HARD && personalNeed.signum() > 0 && haveLock.compareTo(personalNeed) < 0) {
            gaps.add("已锁定的" + anchorZh(anchor) + " ×" + CompactFormat.format(haveLock)
                    + " 还没有达到应锁定的 ×" + CompactFormat.format(personalNeed)
                    + "，尚缺 ×" + CompactFormat.format(personalNeed.subtract(haveLock)));
        }
        if (personalNeed.signum() > 0 && haveReserve.compareTo(personalNeed) < 0) {
            gaps.add("个人准备金里的" + anchorZh(anchor) + " ×" + CompactFormat.format(haveReserve)
                    + " 少于应锁定的 ×" + CompactFormat.format(personalNeed)
                    + "，尚缺 ×" + CompactFormat.format(personalNeed.subtract(haveReserve)));
        }
        if (centralNeed.signum() > 0 && haveCentral.compareTo(centralNeed) < 0) {
            gaps.add("央行准备金账户的央行券 ×" + CompactFormat.format(haveCentral)
                    + " 少于应存的 ×" + CompactFormat.format(centralNeed)
                    + "，尚缺 ×" + CompactFormat.format(centralNeed.subtract(haveCentral)));
        }
        if (!gaps.isEmpty()) {
            return refused(regime, anchor, "不能改为" + regimeZh(regime) + "，计价货币是" + anchorZh(anchor)
                    + "。" + String.join("。", gaps) + "。");
        }
        if (!posting) {
            return new RegimePreview(regime, anchor, true, false, "可以改为" + regimeZh(regime) + "，计价货币是" + anchorZh(anchor) + "。");
        }
        return new RegimePreview(regime, anchor, true, false, "");
    }

    private String successSentence(PersonalNote note, BigDecimal opening) {
        String price = opening == null
                ? CompactFormat.format(note.par()) + " " + anchorZh(note.anchor())
                : "每张 " + CompactFormat.formatRate(opening) + " 钻石";
        String priceRule = switch (note.regime()) {
            case PEG -> "官价按切换时的现价定为" + price + "，以后买卖不改这个官价。";
            case FLOAT -> "开盘价按切换时的官价或面额定为" + price + "。";
            case HARD -> "以后按面额每张 " + CompactFormat.format(note.par()) + " " + anchorZh(note.anchor()) + "定额兑付。";
        };
        return "「" + note.name() + "」已改为" + regimeZh(note.regime()) + "，计价货币是" + anchorZh(note.anchor())
                + "。" + priceRule + "已锁定的抵押不退回，流通 ×" + CompactFormat.format(note.circulation()) + " 不变，也不新发券。";
    }

    private static RegimePreview refused(NoteRegime regime, String anchor, String reason) {
        return new RegimePreview(regime, anchor == null ? "" : anchor, false, false, reason);
    }

    private boolean suspended(PersonalNote note) {
        if (note.circulation().signum() <= 0) {
            return false;
        }
        if (note.regime() == NoteRegime.FLOAT) {
            return effectiveBacking(note).signum() <= 0;
        }
        return effectiveBacking(note).compareTo(note.par()) < 0;
    }

    private BigDecimal officialOrFace(PersonalNote note) {
        if (note.openingDiamonds() != null) {
            return note.openingDiamonds();
        }
        return faceDiamonds(note.anchor(), note.par());
    }

    private BigDecimal faceDiamonds(String anchor, BigInteger par) {
        if ("copper".equals(anchor)) {
            BigInteger rate = copperPerDiamond();
            if (rate == null || rate.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            return new BigDecimal(par).divide(new BigDecimal(rate), FxMath.MC);
        }
        if ("note".equals(anchor)) {
            BigDecimal notesPerDiamond = unitsPer("note", "diamond");
            if (notesPerDiamond == null || notesPerDiamond.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            return new BigDecimal(par).divide(notesPerDiamond, FxMath.MC);
        }
        return new BigDecimal(par);
    }

    private BigInteger personalNeed(NoteRegime regime, String anchor, BigInteger par, BigInteger circulation) {
        if (circulation.signum() <= 0 || par.signum() <= 0) {
            return BigInteger.ZERO;
        }
        if ("note".equals(anchor)) {
            return BigInteger.ZERO;
        }
        if (regime == NoteRegime.HARD) {
            return par.multiply(circulation);
        }
        if (regime == NoteRegime.PEG) {
            return ceiling(par.multiply(circulation), PEG_COMMODITY_COVERAGE);
        }
        return ceiling(par.multiply(circulation), personalNoteCoverage);
    }

    /**
     * A 央行券 anchor is counted in the central-note reserve account, for every regime.
     * Commodity anchors do not use that account.
     */
    private BigInteger centralNeed(NoteRegime regime, String anchor, BigInteger par, BigInteger circulation) {
        if (circulation.signum() <= 0 || par.signum() <= 0 || !"note".equals(anchor)) {
            return BigInteger.ZERO;
        }
        if (regime == NoteRegime.FLOAT) {
            return ceiling(par.multiply(circulation), personalNoteCoverage);
        }
        return par.multiply(circulation);
    }

    private BigInteger pegValue(String anchor, BigInteger par, BigInteger circulation) {
        if ("note".equals(anchor)) {
            return par.multiply(circulation);
        }
        BigDecimal commodity = new BigDecimal(par.multiply(circulation));
        BigDecimal diamonds = "copper".equals(anchor)
                ? commodity.divide(new BigDecimal(copperPerDiamond()), FxMath.MC)
                : commodity;
        BigDecimal notes = unitsPer("note", "diamond");
        if (notes == null || notes.signum() <= 0) {
            return BigInteger.ZERO;
        }
        BigInteger value = diamonds.multiply(notes, FxMath.MC).setScale(0, RoundingMode.CEILING).toBigInteger();
        return value.signum() < 0 ? BigInteger.ZERO : value;
    }

    private static BigInteger ceiling(BigInteger base, BigDecimal ratio) {
        if (base.signum() <= 0 || ratio.signum() <= 0) {
            return BigInteger.ZERO;
        }
        BigInteger value = new BigDecimal(base).multiply(ratio, FxMath.MC).setScale(0, RoundingMode.CEILING).toBigInteger();
        return value.signum() < 0 ? BigInteger.ZERO : value;
    }

    /** Every regime may use one of these anchors. Shop and market goods are not anchors. */
    public static List<String> anchorsOf(NoteRegime regime) {
        if (regime == null) {
            return List.of();
        }
        return List.of("diamond", "copper", "note");
    }

    public static String regimeZh(NoteRegime regime) {
        if (regime == null) {
            return "汇率制度";
        }
        return switch (regime) {
            case FLOAT -> "浮动汇率制";
            case PEG -> "联系汇率制";
            case HARD -> "固定汇率制";
        };
    }

    public static String anchorZh(String anchor) {
        return switch (anchor == null ? "" : anchor) {
            case "copper" -> "铜锭";
            case "note" -> "央行券";
            case "diamond" -> "钻石";
            default -> anchor;
        };
    }

    private static boolean validAnchor(NoteRegime regime, String anchor) {
        if (regime == null || anchor == null) {
            return false;
        }
        return switch (regime) {
            case FLOAT, PEG, HARD -> "diamond".equals(anchor) || "copper".equals(anchor) || "note".equals(anchor) || anchor.matches("personal:[0-9a-fA-F-]{36}");
        };
    }

    public NoteTrade declareBankruptcy(UUID actor, String name, boolean operator) {
        PersonalNote note = name == null || name.isBlank() ? personalNote(actor) : personalNoteByName(name);
        if (note == null) {
            return NoteTrade.fail("none");
        }
        if (!operator && !note.issuer().equals(actor)) {
            return NoteTrade.fail("denied");
        }
        if (note.bankrupt() || reserveSwitching.contains(note.id()) || noteHasPending(note.id())) {
            return NoteTrade.fail("closed");
        }
        if (nestedId(note.anchor()) != null || personalRecovered(note.id()).signum() > 0
                || reserveDust.getOrDefault(note.id(), Money.ZERO).signum() > 0) {
            return NoteTrade.fail("clearing_required");
        }
        String currency = note.reserveCurrency();
        BigInteger reserve = personalReserve(note.issuer(), currency);
        BigInteger backing = reserve.min(note.locked());
        BigInteger owed = note.regime() == NoteRegime.FLOAT
                ? requiredLock(note, note.circulation())
                : note.par().multiply(note.circulation());
        BigInteger shortfall = owed.subtract(backing);
        if (shortfall.signum() < 0) {
            shortfall = BigInteger.ZERO;
        }
        BigInteger gross = note.drawn().add(shortfall);
        BigInteger seized = backing;
        if (seized.signum() > 0) {
            setPersonalReserve(note.issuer(), currency, reserve.subtract(seized));
            if (need(currency) != null) {
                supply(currency).addReserve(seized);
            }
        }
        // Collateral is forfeited to the bank. It does not pay down reserves already drawn.
        // Uncovered promises are added on top of that drawn amount.
        BigInteger residual = gross;
        if (residual.signum() > 0) {
            addDebt(note.issuer(), currency, residual);
        }
        note.declareBankrupt(System.currentTimeMillis(), seized);
        return NoteTrade.ok(note, seized, residual);
    }

    public BigInteger issuerDebt(UUID player, String currency) {
        Map<String, BigInteger> row = issuerDebt.get(player);
        if (row == null || currency == null) {
            return BigInteger.ZERO;
        }
        return row.getOrDefault(currency, BigInteger.ZERO);
    }

    public boolean owesTheBank(UUID player) {
        Map<String, BigInteger> row = issuerDebt.get(player);
        if (row == null) {
            return false;
        }
        for (BigInteger amount : row.values()) {
            if (amount.signum() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Takes commodity counts the caller removes from the issuer and applies them to debt.
     * The goods enter central stock. They are not credited to the issuer's savings.
     */
    public Map<String, BigInteger> seizeCommodities(UUID player, Map<String, BigInteger> available) {
        Map<String, BigInteger> debt = issuerDebt.get(player);
        if (debt == null || debt.isEmpty()) {
            return Map.of();
        }
        BigInteger rate = copperPerDiamond();
        Map<String, BigInteger> take = DebtSeizure.plan(debt, available, rate);
        if (take.isEmpty()) {
            return take;
        }
        for (Map.Entry<String, BigInteger> entry : take.entrySet()) {
            if (need(entry.getKey()) != null && need(entry.getKey()).kind() == CurrencyKind.COMMODITY) {
                supply(entry.getKey()).addReserve(entry.getValue());
            }
        }
        writeDebt(player, DebtSeizure.settle(debt, take, rate));
        return take;
    }

    private void addDebt(UUID player, String currency, BigInteger amount) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        Map<String, BigInteger> row = issuerDebt.computeIfAbsent(player, id -> new LinkedHashMap<>());
        row.merge(currency, amount, BigInteger::add);
    }

    private void writeDebt(UUID player, Map<String, BigInteger> remaining) {
        Map<String, BigInteger> row = new LinkedHashMap<>();
        remaining.forEach((currency, amount) -> {
            if (amount.signum() > 0) {
                row.put(currency, amount);
            }
        });
        if (row.isEmpty()) {
            issuerDebt.remove(player);
        } else {
            issuerDebt.put(player, row);
        }
    }

    /** Anchor units required per newly issued unit; actual support and policy target stay distinct. */
    private BigDecimal personalIssuanceRate(PersonalNote note) {
        PersonalPolicy policy = personalPolicy(note.id());
        if (note.regime() == NoteRegime.PEG && personalActiveExact(note).signum() > 0) {
            return effectiveBackingExact(note).divide(personalActiveExact(note), FxMath.MC).max(policy.lower()).min(policy.upper());
        }
        return policy.target();
    }
    public BigDecimal personalRequiredBackingExact(PersonalNote note) {
        if (note == null || personalActiveExact(note).signum() <= 0) return Money.ZERO;
        if (policies.containsKey(note.id())) {
            return personalActiveExact(note).multiply(personalIssuanceRate(note)).setScale(Money.SCALE, RoundingMode.CEILING);
        }
        // Legacy locked backing retains its original restriction. Previously unlocked floating
        // collateral is nevertheless actual support and cannot be silently drained by this API.
        if (note.regime() == NoteRegime.FLOAT && note.locked().signum() <= 0) return effectiveBackingExact(note);
        return new BigDecimal(note.locked()).max(note.regime() == NoteRegime.FLOAT ? BigDecimal.ZERO
                : personalActiveExact(note).multiply(new BigDecimal(note.par()))).setScale(Money.SCALE, RoundingMode.CEILING);
    }
    public BigDecimal personalFreeReserveExact(PersonalNote note) {
        if (note == null || reserveSwitching.contains(note.id())) return Money.ZERO;
        // Floating support is the full actual reserve, not a guaranteed target; all of it supports circulating holders.
        if (policies.containsKey(note.id()) && note.regime() == NoteRegime.FLOAT && personalActiveExact(note).signum() > 0) return Money.ZERO;
        return Money.of(effectiveBackingExact(note).subtract(personalRequiredBackingExact(note)).max(BigDecimal.ZERO));
    }
    public TxResult withdrawPersonalReserveExact(UUID issuer, BigDecimal amount) {
        PersonalNote note = personalNote(issuer);
        if (!personalTradingAllowed(note) || amount == null || amount.signum() <= 0 || amount.scale() > Money.SCALE)
            return TxResult.fail("invalid", "Invalid reserve withdrawal");
        if (personalFreeReserveExact(note).compareTo(amount) < 0)
            return TxResult.fail("short_reserve", "Only uncommitted reserve can be withdrawn; use explicit reserve replacement to pause settlement");
        String anchor = note.anchor(); UUID nested = nestedId(anchor);
        FractionChange change = quoteFractionChange(anchor, amount);
        setBackingExact(note, effectiveBackingExact(note).subtract(amount));
        if (nested == null) creditBalance(issuer, anchor, amount);
        else setPersonalExact(nested, issuer, personalHoldingExact(nested, issuer).add(amount));
        if ("note".equals(anchor)) supply("note").addLiability(amount);
        String changeMessage = settleFractionChange(issuer, change);
        return TxResult.ok(Money.units(amount), "Uncommitted reserve returned to savings" + changeMessage);
    }

    public NoteTrade issuePersonalNote(UUID issuer, BigInteger amount) {
        PersonalNote note = personalNote(issuer);
        if (note == null) {
            return NoteTrade.fail("none");
        }
        if (!personalTradingAllowed(note)) {
            return NoteTrade.fail("bankrupt");
        }
        if (amount == null || amount.signum() <= 0) {
            return NoteTrade.fail("par");
        }
        BigInteger next = note.circulation().add(amount);
        if (policies.containsKey(note.id())) {
            BigDecimal nextActive = personalActiveExact(note).add(new BigDecimal(amount));
            BigDecimal requiredExact = nextActive.multiply(personalIssuanceRate(note)).setScale(Money.SCALE, RoundingMode.CEILING);
            if (effectiveBackingExact(note).compareTo(requiredExact) < 0) return NoteTrade.fail("reserve");
            note.setLocked(Money.units(requiredExact));
            note.setCirculation(next);
            addHolding(note.id(), issuer, amount);
            return NoteTrade.ok(note, BigInteger.ZERO, BigInteger.ZERO);
        }
        BigInteger required = requiredLock(note, next);
        String backing = note.reserveCurrency();
        if (personalReserve(issuer, backing).compareTo(required) < 0) {
            return NoteTrade.fail("reserve");
        }
        if (shortCentralCover(note, next)) {
            return NoteTrade.fail("central_reserve");
        }
        note.setLocked(required);
        note.setCirculation(next);
        addHolding(note.id(), issuer, amount);
        return NoteTrade.ok(note, BigInteger.ZERO, BigInteger.ZERO);
    }

    /**
     * Adds circulation and credits only the issuer's book holding. Reserves are not locked
     * and no other account is credited. A non-positive amount is refused in full.
     */
    public NoteTrade issuePersonalNoteUnbacked(UUID issuer, BigInteger amount) {
        PersonalNote note = personalNote(issuer);
        if (note == null) {
            return NoteTrade.fail("none");
        }
        if (!personalTradingAllowed(note)) {
            return NoteTrade.fail("bankrupt");
        }
        if (amount == null || amount.signum() <= 0) {
            return NoteTrade.fail("par");
        }
        note.setCirculation(note.circulation().add(amount));
        addHolding(note.id(), issuer, amount);
        return NoteTrade.ok(note, BigInteger.ZERO, BigInteger.ZERO);
    }

    /**
     * Retires circulation from the issuer's own book holding only. Other holders stay put.
     * A short holding refuses the whole amount. The lock is lowered when it would keep
     * deposited reserves that this circulation no longer requires.
     */
    public NoteTrade retirePersonalNote(UUID issuer, BigInteger amount) {
        PersonalNote note = personalNote(issuer);
        if (note == null) {
            return NoteTrade.fail("none");
        }
        if (!personalTradingAllowed(note)) {
            return NoteTrade.fail("bankrupt");
        }
        if (amount == null || amount.signum() <= 0) {
            return NoteTrade.fail("par");
        }
        if (personalHolding(note.id(), issuer).subtract(personalLockedOffer(note.id(), issuer)).compareTo(amount) < 0) {
            return NoteTrade.fail("funds");
        }
        BigInteger centralLiability = supply("note") == null ? BigInteger.ZERO : supply("note").liability();
        addHolding(note.id(), issuer, amount.negate());
        note.setCirculation(note.circulation().subtract(amount));
        relaxLock(note);
        if (supply("note") != null && supply("note").liability().compareTo(centralLiability) != 0) {
            throw new IllegalStateException("personal-note retirement changed central 发行额");
        }
        return NoteTrade.ok(note, BigInteger.ZERO, BigInteger.ZERO);
    }

    /** Pauses or resumes redemption of the issuer's own note. Does not move balances. */
    public NoteTrade setPersonalRedemptionPaused(UUID issuer, boolean paused) {
        PersonalNote note = personalNote(issuer);
        if (note == null) {
            return NoteTrade.fail("none");
        }
        if (!personalTradingAllowed(note)) {
            return NoteTrade.fail("bankrupt");
        }
        note.setRedemptionPaused(paused);
        return NoteTrade.ok(note, BigInteger.ZERO, BigInteger.ZERO);
    }

    public BigInteger centralReserve(UUID issuer) {
        return issuer == null ? BigInteger.ZERO : centralReserveAccounts.getOrDefault(issuer, BigInteger.ZERO);
    }

    /**
     * Funds the reserve that {@link #issuePersonalNote} checks for this note.
     * Copper and diamond anchors move that personal issuance reserve. A 央行券 anchor
     * still uses {@link #depositCentralReserve}.
     */
    public TxResult postIssuanceReserve(UUID issuer, BigInteger amount, boolean deposit) {
        PersonalNote note = personalNote(issuer);
        if (note == null) {
            return TxResult.fail("none", "No personal note");
        }
        if ("note".equals(note.reserveCurrency())) {
            return deposit ? depositCentralReserve(issuer, amount) : withdrawCentralReserve(issuer, amount);
        }
        return deposit ? depositPersonalReserve(issuer, amount) : withdrawPersonalReserve(issuer, amount);
    }

    /**
     * Moves the issuer's copper or diamond savings into the personal issuance reserve
     * {@link #issuePersonalNote} locks. The metal stays in the deposit vault. Central
     * issuance reserves and the 央行券 reserve account are not touched.
     */
    public TxResult depositPersonalReserve(UUID issuer, BigInteger amount) {
        PersonalNote note = personalNote(issuer);
        if (note != null && noteHasPending(note.id())) return TxResult.fail("delivery_pending", "Finish pending delivery first");
        if (note == null) {
            return TxResult.fail("none", "No personal note");
        }
        String currency = note.reserveCurrency();
        if (!"copper".equals(currency) && !"diamond".equals(currency)) {
            return TxResult.fail("anchor", "This note does not use a commodity reserve");
        }
        if (amount == null || amount.signum() <= 0) {
            return TxResult.fail("par", "Invalid amount");
        }
        if (balanceExact(issuer, currency).compareTo(Money.of(amount)) < 0) {
            return TxResult.fail("funds", "Not enough savings");
        }
        debitBalance(issuer, currency, amount);
        setPersonalReserve(issuer, currency, personalReserve(issuer, currency).add(amount));
        return TxResult.ok(amount, "Posted personal reserve");
    }

    /**
     * Returns free personal issuance reserve to savings. Metal already locked for the note stays put.
     */
    public TxResult withdrawPersonalReserve(UUID issuer, BigInteger amount) {
        return withdrawPersonalReserveExact(issuer, amount == null ? null : new BigDecimal(amount));
    }

    /** Moves central notes from savings into the issuer's reserve account. M1 is unchanged. */
    public TxResult depositCentralReserve(UUID issuer, BigInteger amount) {
        if (personalNote(issuer) != null && noteHasPending(personalNote(issuer).id())) return TxResult.fail("delivery_pending", "Finish pending delivery first");
        if (personalNote(issuer) == null) {
            return TxResult.fail("none", "No personal note");
        }
        if (amount == null || amount.signum() <= 0) {
            return TxResult.fail("par", "Invalid amount");
        }
        if (balance(issuer, "note").compareTo(amount) < 0) {
            return TxResult.fail("funds", "Not enough bank notes");
        }
        debitBalance(issuer, "note", amount);
        supply("note").addLiability(amount.negate());
        addCentralReserve(issuer, amount);
        return TxResult.ok(amount, "Posted central reserve");
    }

    /** Returns central notes from the reserve account to savings. Nothing else is drawn on. */
    public TxResult withdrawCentralReserve(UUID issuer, BigInteger amount) {
        PersonalNote own = personalNote(issuer);
        if (own != null && noteHasPending(own.id())) return TxResult.fail("delivery_pending", "Finish pending delivery first");
        if (own != null && "note".equals(own.anchor()) && (policies.containsKey(own.id()) || personalRecovered(own.id()).signum() > 0
                || reserveDust.getOrDefault(own.id(), Money.ZERO).signum() > 0)) {
            return withdrawPersonalReserveExact(issuer, amount == null ? null : new BigDecimal(amount));
        }
        if (personalNote(issuer) == null) {
            return TxResult.fail("none", "No personal note");
        }
        if (amount == null || amount.signum() <= 0) {
            return TxResult.fail("par", "Invalid amount");
        }
        if (centralReserve(issuer).compareTo(amount) < 0) {
            return TxResult.fail("funds", "Not enough central reserve");
        }
        addCentralReserve(issuer, amount.negate());
        creditBalance(issuer, "note", amount);
        supply("note").addLiability(amount);
        return TxResult.ok(amount, "Withdrew central reserve");
    }

    public BigInteger requiredCentralReserve(PersonalNote note) {
        return note == null ? BigInteger.ZERO : requiredCentralReserve(note, personalActiveExact(note).setScale(0, RoundingMode.CEILING).toBigInteger());
    }

    public BigDecimal centralReserveRatio(NoteRegime regime) {
        if (regime == NoteRegime.PEG) {
            return pegCentralRatio;
        }
        if (regime == NoteRegime.HARD) {
            return hardCentralRatio;
        }
        return floatCentralRatio;
    }

    public void setCentralReserveRatio(NoteRegime regime, BigDecimal ratio) {
        if (ratio == null || ratio.signum() < 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("ratio");
        }
        if (regime == NoteRegime.PEG) {
            pegCentralRatio = ratio;
        } else if (regime == NoteRegime.HARD) {
            hardCentralRatio = ratio;
        } else {
            floatCentralRatio = ratio;
        }
    }

    public BigInteger personalOffered(UUID noteId, UUID holder) {
        PersonalNote note = personalNoteById(noteId);
        if (note != null && note.issuer().equals(holder) && note.autoListing()) {
            return personalHolding(noteId, holder);
        }
        return personalLockedOffer(noteId, holder);
    }

    /** Only explicit secondary-holder orders lock funds. Issuer auto offers follow real inventory. */
    private BigInteger personalLockedOffer(UUID noteId, UUID holder) {
        PersonalNote note = personalNoteById(noteId);
        if (note != null && note.issuer().equals(holder) && note.autoListing()) return BigInteger.ZERO;
        return personalOffers.getOrDefault(noteId, Map.of()).getOrDefault(holder, BigInteger.ZERO)
                .min(personalHolding(noteId, holder));
    }

    public TxResult setPersonalAutoListing(UUID issuer, String name, boolean enabled) {
        PersonalNote note = personalNoteByName(name);
        if (note == null || !note.issuer().equals(issuer)) return TxResult.fail("owner", "Only the issuer may change automatic listing");
        if (noteHasPending(note.id())) return TxResult.fail("delivery_pending", "Finish pending delivery first");
        note.setAutoListing(enabled);
        setPersonalOffer(note.id(), issuer, BigInteger.ZERO);
        return TxResult.ok(personalOffered(note.id(), issuer), enabled ? "自动待售已开启，全部现有持币实时挂牌。" : "自动待售已关闭。");
    }

    private java.util.Set<UUID> personalSellers(UUID noteId) {
        java.util.Set<UUID> sellers = new java.util.LinkedHashSet<>(personalOffers.getOrDefault(noteId, Map.of()).keySet());
        PersonalNote note = personalNoteById(noteId);
        if (note != null) sellers.add(note.issuer());
        return sellers;
    }

    public TxResult browsePersonalOffers(UUID viewer) {
        List<String> active = personalNotes.values().stream()
                .filter(note -> !note.bankrupt())
                .filter(note -> availablePersonalOffer(note.id(), viewer).signum() > 0)
                .map(note -> note.name() + " ×" + availablePersonalOffer(note.id(), viewer))
                .toList();
        return TxResult.ok(BigInteger.valueOf(active.size()), active.isEmpty()
                ? "目前没有可买入的个人币挂单。" : "可买入个人币（最大单笔）：" + String.join("、", active));
    }

    public TxResult viewPersonalOffers(UUID viewer, String name) {
        PersonalNote note = personalNoteByName(name);
        if (!personalTradingAllowed(note)) {
            return TxResult.fail(note == null ? "none" : "paused", "");
        }
        return TxResult.ok(availablePersonalOffer(note.id(), viewer), "「" + note.name()
                + "」持有 " + personalHolding(note.id(), viewer)
                + "，我的挂单 " + personalOffered(note.id(), viewer)
                + "，可买入的最大单笔数量 " + availablePersonalOffer(note.id(), viewer) + "。");
    }

    /** Largest single listing the buyer may fill without partial execution. */
    public BigInteger availablePersonalOffer(UUID noteId, UUID buyer) {
        return personalSellers(noteId).stream()
                .filter(seller -> !seller.equals(buyer))
                .map(seller -> personalOffered(noteId, seller))
                .max(BigInteger::compareTo).orElse(BigInteger.ZERO);
    }

    public TxResult listPersonal(UUID holder, String name, BigInteger amount) {
        PersonalNote note = personalNoteByName(name);
        if (note == null) {
            return TxResult.fail("none", "");
        }
        if (noteHasPending(note.id())) return TxResult.fail("delivery_pending", "Cannot edit an offer used by pending delivery");
        if (!personalTradingAllowed(note) && amount.signum() != 0) {
            return TxResult.fail("bankrupt", "");
        }
        if (amount.signum() < 0 || personalHolding(note.id(), holder).compareTo(amount) < 0) {
            return TxResult.fail("funds", "");
        }
        // An explicit quantity is a deliberate manual listing; the UI toggle returns to automatic mode.
        if (note.issuer().equals(holder)) note.setAutoListing(false);
        setPersonalOffer(note.id(), holder, amount);
        return TxResult.ok(amount, "「" + note.name() + "」挂单数量已设为 " + amount + "。");
    }

    public TxResult transferPersonal(UUID sender, UUID recipient, String name, BigInteger amount) {
        PersonalNote note = personalNoteByName(name);
        if (!personalTradingAllowed(note)) {
            return TxResult.fail(note == null ? "none" : "paused", "");
        }
        if (sender.equals(recipient) || amount.signum() <= 0) {
            return TxResult.fail(sender.equals(recipient) ? "self" : "par", "");
        }
        BigInteger free = personalHolding(note.id(), sender).subtract(personalLockedOffer(note.id(), sender));
        if (free.compareTo(amount) < 0) {
            return TxResult.fail("unlisted_funds", "");
        }
        addHolding(note.id(), sender, amount.negate());
        addHolding(note.id(), recipient, amount);
        return TxResult.ok(amount, "已转出「" + note.name() + "」×" + amount + "。");
    }

    /** Direct A/B presentation; only real collateral and real listed inventory settle. */
    public JsonObject quotePersonalCross(UUID buyer, UUID coin, BigInteger amount, String tender) {
        JsonObject out = new JsonObject(); out.addProperty("ok", false); out.addProperty("code", "invalid");
        UUID source = nestedId(tender);
        if (source == null || source.equals(coin) || buyer == null || amount == null || amount.signum() <= 0) return out;
        PersonalPaymentQuote target = quotePersonalPayment(buyer, coin, amount, "note");
        if (!target.success() && !"funds".equals(target.code())) { out.addProperty("code", target.code()); return out; }
        BigDecimal free = personalHoldingExact(source, buyer).subtract(new BigDecimal(personalLockedOffer(source, buyer)));
        ClearingQuote cap = quotePersonalClearing(source, free);
        if (!cap.success()) { out.addProperty("code", cap.code()); return out; }
        // Redeeming B as a collateral leg would change B's reserves, not just transfer B. Never do that implicitly.
        if (cap.legs().stream().anyMatch(leg -> leg.noteId().equals(coin))) {
            out.addProperty("code", "target_in_collateral_chain"); return out;
        }
        BigDecimal unit = "note".equals(cap.currency()) ? BigDecimal.ONE : unitsPer("note", cap.currency());
        if (unit == null || unit.signum() <= 0) { out.addProperty("code", "market"); return out; }
        BigInteger low = BigInteger.ONE, high = free.movePointRight(Money.SCALE).toBigInteger();
        if (cap.amount().multiply(unit).compareTo(target.total()) < 0) { out.addProperty("code", "funds"); return out; }
        while (low.compareTo(high) < 0) {
            BigInteger mid = low.add(high).shiftRight(1);
            ClearingQuote q = quotePersonalClearing(source, new BigDecimal(mid, Money.SCALE));
            if (q.success() && q.amount().multiply(unit).compareTo(target.total()) >= 0) high = mid;
            else low = mid.add(BigInteger.ONE);
        }
        BigDecimal debit = new BigDecimal(low, Money.SCALE);
        ClearingQuote q = quotePersonalClearing(source, debit);
        BigDecimal proceeds = q.amount().multiply(unit);
        if (proceeds.stripTrailingZeros().scale() > Money.SCALE || proceeds.compareTo(target.total()) != 0) {
            out.addProperty("code", "inexact_tender");
            out.addProperty("details", "当前数量在个人币四位精度内无法精确结算，请调整兑换数量或付款币种；不会改用央行券找零。");
            return out;
        }
        if (!"note".equals(q.currency()) && (treasuryExact("note").compareTo(proceeds) < 0
                || supply(q.currency()).depositVaultExact().compareTo(q.amount()) < 0)) {
            out.addProperty("code", "insufficient_treasury"); return out;
        }
        out.addProperty("ok", true); out.addProperty("code", "ok");
        out.addProperty("buyer", buyer.toString()); out.addProperty("coin", coin.toString()); out.addProperty("amount", amount.toString());
        out.addProperty("tender", tender); out.addProperty("paymentAmount", Money.stored(debit));
        out.addProperty("maximum", Money.stored(target.total())); out.addProperty("physical", "0");
        out.addProperty("change", Money.stored(proceeds.subtract(target.total())));
        out.addProperty("rate", debit.divide(new BigDecimal(amount), 12, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
        out.addProperty("fee", "0"); out.addProperty("balance", Money.stored(free));
        out.addProperty("name", personalNoteById(coin).name()); out.addProperty("tenderName", personalNoteById(source).name());
        out.addProperty("chain", q.toString()); out.addProperty("unit", unit.toPlainString());
        UUID seller = listedPersonalCounterparty(coin, buyer, amount);
        BigInteger sellerFee = new BigDecimal(amount).multiply(feeFraction, FxMath.MC).setScale(0, RoundingMode.FLOOR).toBigInteger()
                .min(personalHolding(coin, seller).subtract(amount).max(BigInteger.ZERO));
        out.addProperty("seller", seller.toString()); out.addProperty("sellerFee", sellerFee.toString());
        out.addProperty("details", "直接兑换：付款币逐层扣缴真实准备金并回收，央行券结算真实卖单；目标币准备金不变。按个人币小数精确付款，不另返央行券；卖家手续费 " + sellerFee + " " + personalNoteById(coin).name() + "。");
        return out;
    }

    /** Runtime COW transaction is mandatory. The reference is a server-retained quote nonce. */
    public TxResult buyPersonalCross(UUID buyer, UUID coin, BigInteger amount, String tender, JsonObject expected) {
        String reference = "personal-cross:" + UUID.fromString(expected.get("nonce").getAsString());
        JsonObject prior = clearingReceipts.get(reference);
        String signature = buyer + "|" + coin + "|" + amount + "|" + tender + "|" + expected.toString();
        if (prior != null) return signature.equals(prior.get("signature").getAsString())
                ? TxResult.ok(amount, "该兑换已完成") : TxResult.fail("reference_conflict", "重复请求不一致");
        long age = System.currentTimeMillis() - expected.get("issuedAt").getAsLong();
        if (age < 0 || age > 60000) return TxResult.fail("quote_expired", "兑换报价已过期，请重新确认");
        JsonObject actual = quotePersonalCross(buyer, coin, amount, tender);
        JsonObject comparison = expected.deepCopy(); comparison.remove("nonce"); comparison.remove("issuedAt");
        if (!actual.equals(comparison)) return TxResult.fail("offer_price_moved", "兑换报价已变化，请重新确认");
        if (!actual.get("ok").getAsBoolean()) return TxResult.fail("invalid_quote", "兑换不可用");
        UUID source = nestedId(tender); BigDecimal debit = new BigDecimal(actual.get("paymentAmount").getAsString());
        ClearingQuote q = quotePersonalClearing(source, debit);
        setPersonalExact(source, buyer, personalHoldingExact(source, buyer).subtract(debit));
        for (ClearingLeg leg : q.legs()) {
            PersonalNote note = personalNoteById(leg.noteId());
            setBackingExact(note, effectiveBackingExact(note).subtract(leg.reserve()));
            recoveredNotes.put(note.id(), Money.of(personalRecovered(note.id()).add(leg.notes())));
        }
        BigDecimal total = new BigDecimal(actual.get("maximum").getAsString());
        BigDecimal proceeds = total.add(new BigDecimal(actual.get("change").getAsString()));
        if ("note".equals(q.currency())) supply("note").addLiability(q.amount());
        else {
            collectCustodyIntoTreasury(q.currency(), q.amount());
            treasury.put("note", Money.of(treasuryExact("note").subtract(proceeds)));
        }
        creditBalance(buyer, "note", proceeds);
        // Both legs use the same pre-transaction quote already revalidated above. Our own
        // collateral release must not silently reprice the accepted seller order mid-commit.
        executeListedPersonal(buyer, UUID.fromString(actual.get("seller").getAsString()), personalNoteById(coin), amount, total);
        JsonObject receipt = new JsonObject(); receipt.addProperty("signature", signature); receipt.addProperty("status", "settled");
        receipt.addProperty("timestamp", System.currentTimeMillis()); receipt.addProperty("pendingDelivery", false);
        receipt.addProperty("kind", "personal-cross"); receipt.addProperty("account", "personal-cross"); receipt.addProperty("reference", reference);
        receipt.addProperty("payer", buyer.toString()); receipt.addProperty("currency", "note"); receipt.addProperty("amount", Money.stored(total));
        JsonArray legs = new JsonArray();
        for (ClearingLeg leg : q.legs()) {
            JsonObject row = new JsonObject(); row.addProperty("noteId", leg.noteId().toString());
            row.addProperty("anchor", leg.anchor()); row.addProperty("notes", Money.stored(leg.notes()));
            row.addProperty("reserve", Money.stored(leg.reserve())); legs.add(row);
        }
        receipt.add("legs", legs); clearingReceipts.put(reference, receipt);
        return TxResult.ok(amount, "已完成个人币兑换，无央行券找零");
    }

    /** Exact, executable tender quote. Physical tender is taken only from bank savings. */
    public record PersonalPaymentQuote(boolean success, String code, BigDecimal total,
            BigInteger physical, BigDecimal change) {}

    public PersonalPaymentQuote quotePersonalPayment(UUID buyer, UUID coin, BigInteger amount, String tender) {
        PersonalNote note = personalNoteById(coin);
        TxResult check = note == null || amount == null ? TxResult.fail("none", "")
                : quoteListedPersonal(buyer, note.name(), amount);
        if (!check.success()) return new PersonalPaymentQuote(false, check.code(), Money.ZERO, BigInteger.ZERO, Money.ZERO);
        BigDecimal total = Money.of(personalDiamondPrice(note).multiply(unitsPer("note", "diamond"), FxMath.MC)
                .multiply(new BigDecimal(amount), FxMath.MC));
        if ("note".equals(tender)) return new PersonalPaymentQuote(balanceExact(buyer, "note").compareTo(total) >= 0,
                balanceExact(buyer, "note").compareTo(total) >= 0 ? "ok" : "funds", total, BigInteger.ZERO, Money.ZERO);
        if (!"diamond".equals(tender) && !"copper".equals(tender))
            return new PersonalPaymentQuote(false, "unknown_tender", total, BigInteger.ZERO, Money.ZERO);
        if ("copper".equals(tender)) recomputeOfficialCopperQuote();
        BigDecimal unit = unitsPer("note", tender);
        if (unit == null || unit.signum() <= 0)
            return new PersonalPaymentQuote(false, "market", total, BigInteger.ZERO, Money.ZERO);
        BigInteger pieces = total.divide(unit, 0, java.math.RoundingMode.CEILING).toBigIntegerExact();
        BigDecimal proceeds = unit.multiply(new BigDecimal(pieces));
        // Never silently discard physical value or mint a rounded-up entitlement.
        if (proceeds.stripTrailingZeros().scale() > Money.SCALE)
            return new PersonalPaymentQuote(false, "inexact_tender", total, pieces, Money.ZERO);
        BigDecimal change = Money.of(proceeds.subtract(total));
        String code = balanceExact(buyer, tender).compareTo(new BigDecimal(pieces)) < 0 ? "funds"
                : supply(tender).depositVaultExact().compareTo(new BigDecimal(pieces)) < 0 ? "custody_funds"
                : treasuryExact("note").compareTo(proceeds) < 0 ? "insufficient_treasury" : "ok";
        return new PersonalPaymentQuote("ok".equals(code), code, total, pieces, change);
    }

    /** Must be invoked through BankRuntime.transact: all tender and order legs commit together. */
    public TxResult buyPersonalPayment(UUID buyer, UUID coin, BigInteger amount, String tender,
            BigDecimal expectedTotal, BigInteger expectedPhysical, BigDecimal expectedChange) {
        PersonalPaymentQuote q = quotePersonalPayment(buyer, coin, amount, tender);
        if (!q.success()) return TxResult.fail(q.code(), "报价或支付资金不可用");
        if (expectedTotal == null || expectedPhysical == null || expectedChange == null
                || expectedTotal.compareTo(q.total()) != 0 || !expectedPhysical.equals(q.physical())
                || expectedChange.compareTo(q.change()) != 0)
            return TxResult.fail("offer_price_moved", "支付报价已变化，请重新确认");
        PersonalNote note = personalNoteById(coin);
        UUID seller = listedPersonalCounterparty(coin, buyer, amount);
        if (!"note".equals(tender)) {
            BigDecimal physical = new BigDecimal(q.physical());
            BigDecimal proceeds = q.total().add(q.change());
            debitBalance(buyer, tender, physical);
            collectCustodyIntoTreasury(tender, physical);
            treasury.put("note", Money.of(treasuryExact("note").subtract(proceeds)));
            creditBalance(buyer, "note", proceeds);
        }
        executeListedPersonal(buyer, seller, note, amount, q.total());
        return TxResult.ok(amount, "已买入「" + note.name() + "」×" + amount
                + ("note".equals(tender) ? "，支付央行券 " + Money.stored(q.total())
                : "，扣除银行储蓄" + ("diamond".equals(tender) ? "钻石 " : "铜锭 ") + q.physical()
                    + "，央行券找零 " + Money.stored(q.change())) + "。");
    }

    public TxResult quoteListedPersonal(UUID buyer, String name, BigInteger amount) {
        return settleListedPersonal(buyer, name, amount, null);
    }

    public TxResult buyListedPersonal(UUID buyer, String name, BigInteger amount, BigDecimal maximum) {
        if (maximum == null || maximum.signum() <= 0 || maximum.scale() > Money.SCALE) {
            return TxResult.fail("par", "");
        }
        return settleListedPersonal(buyer, name, amount, maximum);
    }

    private TxResult settleListedPersonal(UUID buyer, String name, BigInteger amount, BigDecimal maximum) {
        PersonalNote note = personalNoteByName(name);
        if (!personalTradingAllowed(note)) {
            return TxResult.fail(note == null ? "none" : "paused", "");
        }
        if (amount.signum() <= 0) {
            return TxResult.fail("par", "");
        }
        UUID seller = listedPersonalCounterparty(note.id(), buyer, amount);
        if (seller == null) {
            return TxResult.fail("offer_empty", "");
        }
        BigDecimal notesPerDiamond = unitsPer("note", "diamond");
        if (notesPerDiamond == null || notesPerDiamond.signum() <= 0) {
            return TxResult.fail("market", "");
        }
        BigDecimal total = personalDiamondPrice(note).multiply(notesPerDiamond, FxMath.MC)
                .multiply(new BigDecimal(amount), FxMath.MC);
        if (total.compareTo(Money.UNIT) < 0) {
            return TxResult.fail("offer_minimum", "");
        }
        BigDecimal payment = Money.of(total);
        if (maximum == null) {
            return TxResult.ok(amount, "买入「" + note.name() + "」×" + amount
                    + "，当前应付央行券 " + Money.stored(payment) + "。");
        }
        if (payment.compareTo(maximum) > 0) {
            return TxResult.fail("offer_price_moved", "");
        }
        if (balanceExact(buyer, "note").compareTo(payment) < 0) {
            return TxResult.fail("funds", "");
        }
        executeListedPersonal(buyer, seller, note, amount, payment);
        return TxResult.ok(amount, "已买入「" + note.name() + "」×" + amount
                + "，支付央行券 " + Money.stored(payment) + "。");
    }

    private void executeListedPersonal(UUID buyer, UUID seller, PersonalNote note, BigInteger amount, BigDecimal payment) {
        debitBalance(buyer, "note", payment);
        creditBalance(seller, "note", payment);
        setPersonalOffer(note.id(), seller, personalOffered(note.id(), seller).subtract(amount));
        addHolding(note.id(), seller, amount.negate());
        addHolding(note.id(), buyer, amount);
        note.addBuy(amount);
        retainTradeFee(note, seller, amount);
    }

    private UUID listedPersonalCounterparty(UUID noteId, UUID buyer, BigInteger amount) {
        return personalSellers(noteId).stream()
                .filter(seller -> !seller.equals(buyer) && personalOffered(noteId, seller).compareTo(amount) >= 0)
                .findFirst().orElse(null);
    }

    private void setPersonalOffer(UUID noteId, UUID holder, BigInteger amount) {
        if (amount.signum() > 0) {
            personalOffers.computeIfAbsent(noteId, ignored -> new LinkedHashMap<>()).put(holder, amount);
            return;
        }
        Map<UUID, BigInteger> row = personalOffers.get(noteId);
        if (row != null) {
            row.remove(holder);
            if (row.isEmpty()) {
                personalOffers.remove(noteId);
            }
        }
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
        if (!personalTradingAllowed(note)) {
            return NoteTrade.fail("bankrupt");
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
        // Mark to the live quote. Nothing here remembers what the seller originally paid.
        BigDecimal diamondEach = personalDiamondPrice(note);
        if (diamondEach.signum() <= 0) return NoteTrade.fail("reserve");
        BigDecimal diamonds = diamondEach.multiply(new BigDecimal(amount), FxMath.MC);
        BigDecimal notesPerDiamond = unitsPer("note", "diamond");
        // Charge at most one ledger quantum above the quote, never free sub-quantum trades.
        BigDecimal central = diamonds.multiply(notesPerDiamond, FxMath.MC).setScale(Money.SCALE, RoundingMode.CEILING);
        if (central.signum() <= 0) return NoteTrade.fail("market");
        UUID payer = buy ? actor : note.issuer();
        UUID payee = buy ? note.issuer() : actor;
        UUID noteSource = buy ? note.issuer() : actor;
        UUID noteDest = buy ? actor : note.issuer();
        BigInteger sourceAvailable = buy ? personalOffered(note.id(), noteSource)
                : personalHolding(note.id(), noteSource).subtract(personalLockedOffer(note.id(), noteSource));
        if (sourceAvailable.compareTo(amount) < 0) {
            return NoteTrade.fail("empty");
        }
        if (balanceExact(payer, "note").compareTo(central) < 0) {
            return NoteTrade.fail("funds");
        }
        if (central.signum() > 0) {
            debitBalance(payer, "note", central);
            creditBalance(payee, "note", central);
        }
        if (buy) setPersonalOffer(note.id(), noteSource, personalOffered(note.id(), noteSource).subtract(amount));
        addHolding(note.id(), noteSource, amount.negate());
        addHolding(note.id(), noteDest, amount);
        if (buy) {
            note.addBuy(amount);
        } else {
            note.addSell(amount);
        }
        retainTradeFee(note, note.issuer(), amount);
        return NoteTrade.okExact(note, central);
    }

    /**
     * Swap {@code receiveAmount} of {@code receiveName} for whole notes of {@code payName}
     * at {@code P_receive / P_pay}. The remainder is paid in fractional central notes.
     * Holdings move between the player and a counterparty. Issuer reserves stay put.
     * The issuer cannot take the other side of their own note.
     */
    public SwapTrade swapPersonalNotes(UUID player, String payName, String receiveName, BigInteger receiveAmount) {
        PersonalNote pay = personalNoteByName(payName);
        PersonalNote receive = personalNoteByName(receiveName);
        if (pay == null || receive == null) {
            return SwapTrade.fail("none");
        }
        if (!personalTradingAllowed(pay) || !personalTradingAllowed(receive)) {
            return SwapTrade.fail("bankrupt");
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
        if (personalHolding(pay.id(), player).subtract(personalLockedOffer(pay.id(), player)).compareTo(quote.payNotes()) < 0) {
            return SwapTrade.fail("empty");
        }
        if (balanceExact(player, "note").compareTo(quote.centralExact()) < 0) {
            return SwapTrade.fail("funds");
        }
        addHolding(pay.id(), player, quote.payNotes().negate());
        addHolding(pay.id(), counterparty, quote.payNotes());
        setPersonalOffer(receive.id(), counterparty, personalOffered(receive.id(), counterparty).subtract(receiveAmount));
        addHolding(receive.id(), counterparty, receiveAmount.negate());
        addHolding(receive.id(), player, receiveAmount);
        if (quote.centralExact().signum() > 0) {
            debitBalance(player, "note", quote.centralExact());
            creditBalance(counterparty, "note", quote.centralExact());
        }
        // Buying pressure lifts the note that was received. Selling pressure leans on the note that was paid.
        // The next trade reads those flows; it does not reuse this fill.
        receive.addBuy(receiveAmount);
        pay.addSell(quote.payNotes());
        retainTradeFee(receive, player, receiveAmount);
        retainTradeFee(pay, counterparty, quote.payNotes());
        return SwapTrade.okExact(pay, receive, quote.payNotes(), receiveAmount, quote.centralExact());
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
        if (note.bankrupt() || reserveSwitching.contains(note.id()) || noteHasPending(note.id())) {
            return Redemption.fail("bankrupt");
        }
        if (note.redemptionPaused()) {
            return Redemption.fail("paused", note);
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
        addHolding(note.id(), player, priced.retired().negate());
        applyRedemption(player, note, priced.retired(), priced);
        retainTradeFee(note, player, priced.retired());
        return priced;
    }

    /** Same redemption, burning outstanding paper instead of a book-entry holding. */
    public Redemption redeemPersonalPaper(UUID player, UUID noteId, BigInteger face, BigInteger sheets, String payout) {
        PersonalNote note = personalNotes.get(noteId);
        if (note == null || face == null || face.signum() <= 0 || sheets == null || sheets.signum() <= 0) {
            return Redemption.fail("note_tampered");
        }
        if (note.bankrupt() || reserveSwitching.contains(note.id()) || noteHasPending(note.id())) {
            return Redemption.fail("bankrupt");
        }
        if (note.redemptionPaused()) {
            return Redemption.fail("paused", note);
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
        BigInteger copies = sheets.subtract(acceptSheets);
        BigInteger burn = priced.retired().divide(face);
        if (face.compareTo(BigInteger.ONE) > 0 && priced.retired().compareTo(amount) < 0) {
            return Redemption.fail("suspended");
        }
        addPaper(NoteSeries.key(null, noteId, face), burn.negate());
        applyRedemption(player, note, priced.retired(), priced);
        retainTradeFee(note, player, priced.retired());
        return new Redemption(true, priced.code(), note, priced.retired(), priced.diamonds(), priced.copper(),
                priced.central(), priced.discounted(), copies.add(burn));
    }

    private Redemption priceRedemption(PersonalNote note, BigInteger amount, String payout) {
        return Redemption.fail("route_to_savings");
    }

    /** Peg and hard redemption pay the official rate and never reprice. A short reserve caps the count. */
    private Redemption priceFixedRedemption(PersonalNote note, BigInteger amount, String payout) {
        String anchor = note.reserveCurrency();
        if (!anchor.equals(payout)) {
            return Redemption.fail("payout");
        }
        BigInteger available = effectiveBacking(note);
        if (available.signum() <= 0 || note.par().signum() <= 0) {
            return Redemption.fail("suspended");
        }
        BigInteger affordable = available.divide(note.par());
        if (affordable.signum() <= 0) {
            return Redemption.fail("suspended");
        }
        BigInteger retire = amount.min(affordable);
        BigInteger pay = retire.multiply(note.par());
        BigInteger diamonds = "diamond".equals(anchor) ? pay : BigInteger.ZERO;
        BigInteger copper = "copper".equals(anchor) ? pay : BigInteger.ZERO;
        BigInteger central = "note".equals(anchor) ? pay : BigInteger.ZERO;
        boolean capped = retire.compareTo(amount) < 0;
        return new Redemption(true, capped ? "capped" : "ok", note, retire, diamonds, copper, central, false, BigInteger.ZERO);
    }

    private void applyRedemption(UUID player, PersonalNote note, BigInteger amount, Redemption priced) {
        if (note.regime() != NoteRegime.FLOAT) {
            BigInteger consumed = amount.multiply(note.par());
            note.setCirculation(note.circulation().subtract(amount));
            releaseBacking(note, consumed);
            relaxLock(note);
            note.addDrawn(consumed);
            payRedemption(player, priced);
            return;
        }
        BigInteger available = effectiveBacking(note);
        BigInteger consumed = priced.discounted()
                ? available
                : parConsumed(note, amount, available);
        if (consumed.compareTo(available) > 0) {
            consumed = available;
        }
        note.setCirculation(note.circulation().subtract(amount));
        releaseBacking(note, consumed);
        relaxLock(note);
        note.addDrawn(consumed);
        payRedemption(player, priced);
    }

    /** Pays redemption out of the deposited reserve, not out of a forced lock. */
    private void releaseBacking(PersonalNote note, BigInteger consumed) {
        if (consumed == null || consumed.signum() <= 0) {
            return;
        }
        if ("note".equals(note.reserveCurrency())) {
            BigInteger take = consumed.min(centralReserve(note.issuer()));
            if (take.signum() > 0) {
                addCentralReserve(note.issuer(), take.negate());
            }
            return;
        }
        BigInteger next = personalReserve(note.issuer(), note.reserveCurrency()).subtract(consumed);
        if (next.signum() < 0) {
            next = BigInteger.ZERO;
        }
        setPersonalReserve(note.issuer(), note.reserveCurrency(), next);
    }

    /** Drops a lock that would pin reserves this circulation no longer requires. Never raises it. */
    private void relaxLock(PersonalNote note) {
        BigInteger reserve = "note".equals(note.reserveCurrency())
                ? centralReserve(note.issuer())
                : personalReserve(note.issuer(), note.reserveCurrency());
        BigInteger cap = requiredLock(note, personalActiveExact(note).setScale(0, RoundingMode.CEILING).toBigInteger());
        if (cap.compareTo(reserve) > 0) {
            cap = reserve.signum() < 0 ? BigInteger.ZERO : reserve;
        }
        if (note.locked().compareTo(cap) > 0) {
            note.setLocked(cap);
        }
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
        if (need(currency).kind() == CurrencyKind.COMMODITY) {
            addSavings(currency, amount);
        } else {
            supply(currency).addLiability(amount);
        }
    }

    private static Supply openingSupply(CurrencyDef def) {
        BigInteger seed = def.initialReserve() == null ? BigInteger.ZERO : def.initialReserve();
        return new Supply(def.id(), seed, BigInteger.ZERO, seed, BigInteger.ZERO);
    }

    /** Savings custody only. Issuance reserves are not touched. */
    private void addSavings(String currency, BigInteger delta) {
        addSavings(currency, Money.of(delta));
    }

    private void addSavings(String currency, BigDecimal delta) {
        Supply self = supply(currency);
        self.addDepositVault(delta);
        self.addLiability(delta);
    }

    /**
     * The payer gives up {@code gross}. Only the pre-fee principal enters issuance reserves or is
     * extinguished as convertibility. The fee enters central-owned treasury with a non-asset interest budget.
     */
    private void takePayment(UUID player, CurrencyDef pay, BigDecimal gross, BigDecimal fee) {
        debitBalance(player, pay.id(), gross);
        BigDecimal principal = Money.of(gross.subtract(fee));
        if (pay.kind() == CurrencyKind.COMMODITY) {
            addSavings(pay.id(), gross.negate());
            if (principal.signum() > 0) {
                supply(pay.id()).addReserve(principal);
            }
        } else {
            supply(pay.id()).addLiability(gross.negate());
        }
        if (fee.signum() > 0) {
            addPool(pay.id(), fee);
        }
    }

    /** Issuance reserves pay a commodity into savings, or a virtual unit into public M1. */
    private void payIssuanceIntoSavings(UUID player, String currency, BigInteger amount) {
        CurrencyDef def = need(currency);
        creditBalance(player, currency, amount);
        if (def != null && def.kind() == CurrencyKind.COMMODITY) {
            supply(currency).addReserve(amount.negate());
            addSavings(currency, amount);
        } else {
            supply(currency).addLiability(amount);
        }
    }

    private static boolean validPayout(String payout) {
        return "diamond".equals(payout) || "copper".equals(payout) || "note".equals(payout);
    }

    private UUID noteCounterparty(PersonalNote note, BigInteger amount, UUID actor) {
        return listedPersonalCounterparty(note.id(), actor, amount);
    }

    private BigInteger requiredLock(PersonalNote note, BigInteger circulation) {
        if (circulation.signum() <= 0) {
            return BigInteger.ZERO;
        }
        if ("note".equals(note.reserveCurrency())) {
            return BigInteger.ZERO;
        }
        if (note.regime() == NoteRegime.HARD) {
            return note.par().multiply(circulation);
        }
        if (note.regime() == NoteRegime.PEG) {
            return ceiling(note.par().multiply(circulation), PEG_COMMODITY_COVERAGE);
        }
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
        limitPersonalOffer(noteId, player, next);
    }

    private void limitPersonalOffer(UUID noteId, UUID player, BigInteger remaining) {
        BigInteger offered = personalOffers.getOrDefault(noteId, Map.of()).getOrDefault(player, BigInteger.ZERO);
        if (offered.compareTo(remaining) > 0) {
            setPersonalOffer(noteId, player, remaining);
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
        this.interestFraction = config.interestFraction();
        this.personalInterestFraction = config.personalInterestFraction();
        this.floatCentralRatio = config.floatCentralReserve();
        this.pegCentralRatio = config.pegCentralReserve();
        this.hardCentralRatio = config.hardCentralReserve();
        this.clampMin = config.clampMin();
        this.clampMax = config.clampMax();
        for (CurrencyDef def : config.currencies()) {
            currencies.putIfAbsent(def.id(), def);
            alphas.put(def.id(), def.alphaPermille());
            supplies.computeIfAbsent(def.id(), id -> openingSupply(def));
        }
    }

    /** Exact, native-unit monetary aggregates. M1 contains M0; never add them together.
     * These cover registered book entries and issued paper, not unobserved inventory/world items.
     * Treasury is separately reported, not public M1. No current FX quote feeds these totals.
     */
    public BigDecimal centralM0Exact() {
        CurrencyDef note = primaryVirtual();
        return note == null ? Money.ZERO : Money.of(paperValue(note.id()));
    }

    public BigDecimal centralDepositsExact() {
        CurrencyDef note = primaryVirtual();
        return note == null ? Money.ZERO : bookSavingsExact(note.id());
    }

    public BigDecimal centralM1Exact() {
        return Money.of(centralM0Exact().add(centralDepositsExact()));
    }

    /** Non-FX-valued broad-money components, each in its own native currency units.
     * An outer personal currency already represents its pledged inner units: remove those inner units.
     * This is a vector, NOT a number to sum across different currency denominations.
     */
    public Map<String, BigDecimal> broadMoneyComponentsExact() {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        CurrencyDef central = primaryVirtual();
        if (central != null) result.put(central.id(), centralM1Exact());
        for (CurrencyDef def : currencies.values()) {
            if (def.kind() == CurrencyKind.COMMODITY) result.put(def.id(), bookSavingsExact(def.id()));
        }
        for (PersonalNote note : personalNotes.values()) {
            if (note.bankrupt() || reserveSwitching.contains(note.id())) continue;
            BigDecimal active = personalActiveExact(note);
            for (PersonalNote outer : personalNotes.values()) {
                if (("personal:" + note.id()).equals(outer.anchor())) active = active.subtract(effectiveBackingExact(outer));
            }
            result.put("personal:" + note.id(), Money.of(active.max(BigDecimal.ZERO)));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    public BigInteger centralM0() {
        CurrencyDef note = primaryVirtual();
        return note == null ? BigInteger.ZERO : paperValue(note.id());
    }

    public BigInteger centralDeposits() {
        CurrencyDef note = primaryVirtual();
        return note == null ? BigInteger.ZERO : bookSavings(note.id());
    }

    public BigInteger centralM1() {
        return centralM0().add(centralDeposits());
    }

    /** M1 plus personal notes and commodity savings, valued in central notes at the live rate. */
    public BigDecimal centralM2() {
        BigDecimal notesPerDiamond = unitsPer("note", "diamond");
        BigDecimal quasi = BigDecimal.ZERO;
        if (notesPerDiamond != null && notesPerDiamond.signum() > 0) {
            for (PersonalNote note : personalNotes.values()) {
                if (note.bankrupt() || reserveSwitching.contains(note.id())) {
                    continue;
                }
                BigDecimal publicActive = personalActiveExact(note);
                // Nested pledged currency is not independently spendable quasi-money; its value is already in the outer note.
                for (PersonalNote outer : personalNotes.values()) {
                    if (("personal:" + note.id()).equals(outer.anchor())) publicActive = publicActive.subtract(effectiveBackingExact(outer));
                }
                BigDecimal diamonds = personalDiamondPrice(note).multiply(publicActive.max(BigDecimal.ZERO), FxMath.MC);
                quasi = quasi.add(diamonds.multiply(notesPerDiamond, FxMath.MC), FxMath.MC);
            }
            BigDecimal notesPerCopper = unitsPer("note", "copper");
            if (notesPerCopper != null && notesPerCopper.signum() > 0) {
                quasi = quasi.add(bookSavingsExact("copper").multiply(notesPerCopper, FxMath.MC), FxMath.MC);
            }
            quasi = quasi.add(bookSavingsExact("diamond").multiply(notesPerDiamond, FxMath.MC), FxMath.MC);
        }
        return centralM1Exact().add(quasi, FxMath.MC);
    }

    public BigInteger personalM0(PersonalNote note) {
        return note == null ? BigInteger.ZERO : personalPaperValue(note.id());
    }

    /** Book entry plus paper. Unused issuance capacity is not included. */
    public BigInteger personalM1(PersonalNote note) {
        return note == null ? BigInteger.ZERO : Money.units(personalActiveExact(note));
    }

    private BigInteger publicM1(String currency) {
        return bookSavings(currency).add(paperValue(currency));
    }

    /**
     * Personal-note M1 is {@code circulation} (book entry plus paper), not those two added twice.
     * Float counts that M1 directly. Peg and hard convert the peg value into central notes.
     */
    private boolean shortCentralCover(PersonalNote note, BigInteger circulation) {
        BigInteger need = requiredCentralReserve(note, circulation);
        return need.signum() > 0 && centralReserve(note.issuer()).compareTo(need) < 0;
    }

    private BigInteger requiredCentralReserve(PersonalNote note, BigInteger circulation) {
        if (note == null || circulation.signum() <= 0 || note.par().signum() <= 0) {
            return BigInteger.ZERO;
        }
        if ("note".equals(note.reserveCurrency())) {
            return centralNeed(note.regime(), "note", note.par(), circulation);
        }
        return BigInteger.ZERO;
    }

    private BigInteger pegValueInNotes(PersonalNote note, BigInteger circulation) {
        if ("note".equals(note.reserveCurrency())) {
            return circulation.multiply(note.par());
        }
        BigDecimal commodity = new BigDecimal(circulation.multiply(note.par()));
        BigDecimal diamonds = "copper".equals(note.reserveCurrency())
                ? commodity.divide(new BigDecimal(copperPerDiamond()), FxMath.MC)
                : commodity;
        BigDecimal notes = unitsPer("note", "diamond");
        if (notes == null || notes.signum() <= 0) {
            return BigInteger.ZERO;
        }
        BigInteger value = diamonds.multiply(notes, FxMath.MC).setScale(0, RoundingMode.CEILING).toBigInteger();
        return value.signum() < 0 ? BigInteger.ZERO : value;
    }

    private static BigInteger share(BigInteger base, BigDecimal ratio) {
        if (base.signum() <= 0 || ratio.signum() <= 0) {
            return BigInteger.ZERO;
        }
        BigInteger value = new BigDecimal(base).multiply(ratio, FxMath.MC).setScale(0, RoundingMode.CEILING).toBigInteger();
        return value.signum() < 0 ? BigInteger.ZERO : value;
    }

    private void addCentralReserve(UUID issuer, BigInteger delta) {
        BigInteger next = centralReserve(issuer).add(delta);
        if (next.signum() < 0) {
            throw new IllegalStateException("central reserve underflow");
        }
        if (next.signum() == 0) {
            centralReserveAccounts.remove(issuer);
        } else {
            centralReserveAccounts.put(issuer, next);
        }
    }

    private BigInteger paperValue(String currency) {
        String prefix = "c/" + currency + "/";
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

    /**
     * Old snapshots stored depositor commodity and issuance stock in one reserve, and counted
     * the opening note seed as a liability. Split them without changing player balances.
     * If subtracting deposits would push issuance reserves below the locked amount, issuance
     * stays at the lock and the savings vault still matches deposits.
     * Sub-accounts are zero on those old snapshots, so this split does not move them.
     */
    private void migrateMixedVaults() {
        for (CurrencyDef def : currencies.values()) {
            Supply self = supplies.get(def.id());
            if (self == null) {
                continue;
            }
            if (def.kind() == CurrencyKind.COMMODITY) {
                BigInteger claims = bookSavings(def.id());
                BigInteger issuance = self.reserve().subtract(claims);
                if (issuance.compareTo(self.locked()) < 0) {
                    issuance = self.locked();
                }
                if (issuance.signum() < 0) {
                    issuance = BigInteger.ZERO;
                }
                self.setReserve(issuance);
                self.setDepositVault(claims);
                self.setLiability(claims);
            } else {
                self.setLiability(publicM1(def.id()));
            }
        }
    }

    public void checkInvariants() {
        for (Map.Entry<UUID, BigDecimal> entry : recoveredNotes.entrySet()) {
            PersonalNote note = personalNoteById(entry.getKey());
            if (note == null || entry.getValue().signum() < 0 || entry.getValue().compareTo(new BigDecimal(note.circulation())) > 0)
                throw new AssertionError("invalid recovered personal currency");
        }
        for (BigDecimal fraction : reserveDust.values()) {
            if (fraction.signum() < 0 || fraction.compareTo(BigDecimal.ONE) >= 0) throw new AssertionError("invalid reserve fraction");
        }
        for (Map<String, BigDecimal> account : settledAssets.values()) {
            for (BigDecimal asset : account.values()) if (asset.signum() < 0) throw new AssertionError("negative system asset");
        }
        for (CurrencyDef def : currencies.values()) {
            Supply supply = supplies.get(def.id());
            if (supply == null) {
                continue;
            }
            if (supply.reserveExact().signum() < 0 || supply.lockedExact().signum() < 0
                    || supply.liabilityExact().signum() < 0 || supply.depositVaultExact().signum() < 0) {
                throw new AssertionError(def.id() + " went negative");
            }
            if (supply.lockedExact().compareTo(supply.reserveExact()) > 0) {
                throw new AssertionError(def.id() + " locked exceeds issuance reserve");
            }
            if (def.kind() != CurrencyKind.COMMODITY && supply.liabilityExact().compareTo(publicM1Exact(def.id()).add(treasuryExact(def.id()))) < 0) {
                throw new AssertionError(def.id() + " claims " + publicM1Exact(def.id()) + " exceed 发行额 " + supply.liabilityExact());
            }
        }
        for (BigDecimal owned : treasury.values()) {
            if (owned.signum() < 0) throw new AssertionError("negative central treasury");
        }
        for (BigDecimal pooled : interestPool.values()) {
            if (pooled.signum() < 0) {
                throw new AssertionError("negative interest pool");
            }
        }
        for (BigInteger amount : shopOperating.values()) {
            if (amount.signum() < 0) {
                throw new AssertionError("negative shop operating account");
            }
        }
        for (BigInteger amount : landGrant.values()) {
            if (amount.signum() < 0) {
                throw new AssertionError("negative land-grant account");
            }
        }
        for (Map<String, BigDecimal> bals : accounts.values()) {
            for (BigDecimal bal : bals.values()) {
                if (bal.signum() < 0) {
                    throw new AssertionError("negative balance");
                }
            }
        }
        for (PersonalNote note : personalNotes.values()) {
            if (holdingTotalExact(note.id()).add(new BigDecimal(personalPaperValue(note.id()))).compareTo(new BigDecimal(note.circulation())) != 0) {
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
        CurrencyDef note = primaryVirtual();
        String noteId = note == null ? "note" : note.id();
        sb.append("shopOperating ").append(noteId).append('=').append(shopOperating(noteId)).append('\n');
        sb.append("landGrant ").append(noteId).append('=').append(landGrant(noteId)).append('\n');
        return sb.toString();
    }

    public String toJson() {
        recomputeOfficialNoteQuote();
        JsonObject root = new JsonObject();
        root.addProperty("currencyNameVersion", 1);
        root.addProperty("centralIssuanceTreasuryVersion", 1);
        root.addProperty("version", SNAPSHOT_VERSION);
        root.addProperty("checkpoint", checkpoint);
        JsonObject marketReceiptsJson = new JsonObject();
        marketReceipts.forEach(marketReceiptsJson::addProperty);
        root.add("marketReceipts", marketReceiptsJson);
        root.addProperty("numeraire", numeraire);
        root.addProperty("fee", feeFraction.toPlainString());
        if (liveCopperPerDiamond != null) {
            root.addProperty("liveCopperPerDiamond", liveCopperPerDiamond.toPlainString());
        }
        if (officialDiamondsPerNote != null) {
            root.addProperty("diamondsPerNote", officialDiamondsPerNote.signum() == 0
                    ? "0" : officialDiamondsPerNote.toPlainString());
        }
        if (liveNotesPerDiamond != null) {
            root.addProperty("liveNotesPerDiamond", liveNotesPerDiamond.toPlainString());
        }
        root.addProperty("interestRate", interestFraction.toPlainString());
        root.addProperty("personalInterestRate", personalInterestFraction.toPlainString());
        root.addProperty("floatCentralRatio", floatCentralRatio.toPlainString());
        root.addProperty("pegCentralRatio", pegCentralRatio.toPlainString());
        root.addProperty("hardCentralRatio", hardCentralRatio.toPlainString());
        JsonObject posted = new JsonObject();
        centralReserveAccounts.forEach((id, amount) -> posted.addProperty(id.toString(), amount.toString()));
        root.add("centralReserveAccounts", posted);
        root.addProperty("lastInterestDay", lastInterestDay);
        JsonObject pool = new JsonObject();
        interestPool.forEach((id, amount) -> pool.addProperty(id, Money.stored(amount)));
        root.add("interestPool", pool);
        root.addProperty("feeTreasuryVersion", 1);
        JsonObject owned = new JsonObject();
        treasury.forEach((id, amount) -> owned.addProperty(id, Money.stored(amount)));
        root.add("centralTreasury", owned);
        root.add("systemFundingLog", systemFundingLog.deepCopy());
        JsonObject dust = new JsonObject();
        interestDust.forEach((id, amount) -> dust.addProperty(id.toString(), amount.toPlainString()));
        root.add("interestDust", dust);
        writeAggregate(root, "shopOperating", shopOperating);
        writeDust(root, "shopFraction", shopFraction);
        JsonObject personalShop = new JsonObject();
        shopPersonal.forEach((id, amount) -> personalShop.addProperty(id.toString(), Money.stored(amount)));
        root.add("shopPersonal", personalShop);
        JsonObject holderFractions = new JsonObject();
        holdingFraction.forEach((id, row) -> {
            JsonObject holders = new JsonObject();
            row.forEach((holder, amount) -> holders.addProperty(holder.toString(), Money.stored(amount)));
            holderFractions.add(id.toString(), holders);
        });
        root.add("holdingFraction", holderFractions);
        writeAggregate(root, "landGrant", landGrant);
        writeDust(root, "shopInterestDust", shopInterestDust);
        writeDust(root, "landInterestDust", landInterestDust);
        JsonObject personalDust = new JsonObject();
        personalInterestDust.forEach((noteId, row) -> {
            JsonObject holders = new JsonObject();
            row.forEach((player, fraction) -> holders.addProperty(player.toString(), fraction.toPlainString()));
            if (holders.size() > 0) {
                personalDust.add(noteId.toString(), holders);
            }
        });
        root.add("personalInterestDust", personalDust);
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
            o.addProperty("reserve", Money.stored(supply.reserveExact()));
            o.addProperty("liability", Money.stored(supply.liabilityExact()));
            o.addProperty("anchor", Money.stored(supply.anchorExact()));
            o.addProperty("locked", Money.stored(supply.lockedExact()));
            o.addProperty("depositVault", Money.stored(supply.depositVaultExact()));
            supplyJson.add(supply.currency(), o);
        }
        root.add("supplies", supplyJson);

        JsonObject accountJson = new JsonObject();
        for (Map.Entry<UUID, Map<String, BigDecimal>> entry : accounts.entrySet()) {
            JsonObject bals = new JsonObject();
            entry.getValue().forEach((c, v) -> {
                if (v.signum() != 0) {
                    bals.addProperty(c, Money.stored(v));
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
        JsonObject debtJson = new JsonObject();
        for (Map.Entry<UUID, Map<String, BigInteger>> entry : issuerDebt.entrySet()) {
            JsonObject row = new JsonObject();
            entry.getValue().forEach((currency, amount) -> {
                if (amount.signum() > 0) {
                    row.addProperty(currency, amount.toString());
                }
            });
            if (row.size() > 0) {
                debtJson.add(entry.getKey().toString(), row);
            }
        }
        root.add("issuerDebt", debtJson);
        root.addProperty("personalNoteCoverage", personalNoteCoverage.toPlainString());
        JsonArray noteJson = new JsonArray();
        for (PersonalNote note : personalNotes.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", note.id().toString());
            o.addProperty("issuer", note.issuer().toString());
            o.addProperty("name", note.name());
            o.addProperty("regime", note.regime().name());
            o.addProperty("anchor", note.anchor());
            o.addProperty("copperPar", note.copperPar());
            o.addProperty("par", note.par().toString());
            o.addProperty("circulation", note.circulation().toString());
            o.addProperty("buys", note.buys().toString());
            o.addProperty("sells", note.sells().toString());
            o.addProperty("locked", note.locked().toString());
            o.addProperty("redemptionPaused", note.redemptionPaused());
            o.addProperty("autoListing", note.autoListing());
            o.addProperty("drawn", note.drawn().toString());
            o.addProperty("bankrupt", note.bankrupt());
            o.addProperty("closedAt", note.closedAtEpochMs());
            o.addProperty("released", note.released().toString());
            o.addProperty("interestPool", note.interestPool().toString());
            o.addProperty("interestDay", note.interestDay());
            if (note.openingDiamonds() != null) {
                o.addProperty("openingDiamonds", note.openingDiamonds().toPlainString());
            }
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
        JsonObject offerJson = new JsonObject();
        personalOffers.forEach((noteId, offers) -> {
            JsonObject row = new JsonObject();
            offers.forEach((holder, amount) -> row.addProperty(holder.toString(), amount.toString()));
            offerJson.add(noteId.toString(), row);
        });
        root.add("personalOffers", offerJson);

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
            o.addProperty("quarterTurns", outlet.quarterTurns());
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
        JsonObject clearing = new JsonObject();
        JsonObject clearingDust = new JsonObject(), recovered = new JsonObject(), policyRows = new JsonObject(), assets = new JsonObject(), receipts = new JsonObject();
        reserveDust.forEach((id, value) -> clearingDust.addProperty(id.toString(), Money.stored(value)));
        recoveredNotes.forEach((id, value) -> recovered.addProperty(id.toString(), Money.stored(value)));
        policies.forEach((id, value) -> { JsonObject row = new JsonObject(); row.addProperty("target", value.target()); row.addProperty("lower", value.lower()); row.addProperty("upper", value.upper()); policyRows.add(id.toString(), row); });
        settledAssets.forEach((account, balances) -> { JsonObject row = new JsonObject(); balances.forEach((currency, value) -> row.addProperty(currency, Money.stored(value))); assets.add(account, row); });
        clearingReceipts.forEach((reference, value) -> receipts.add(reference, value));
        JsonArray switching = new JsonArray(); reserveSwitching.forEach(id -> switching.add(id.toString()));
        clearing.add("reserveDust", clearingDust); clearing.add("recovered", recovered); clearing.add("policies", policyRows); clearing.add("assets", assets); clearing.add("receipts", receipts); clearing.add("switching", switching);
        JsonObject landFractions = new JsonObject(); landGrantFraction.forEach((currency, value) -> landFractions.addProperty(currency, Money.stored(value)));
        clearing.add("landGrantFraction", landFractions);
        root.add("recursiveClearing", clearing);
        return GSON.toJson(root);
    }

    /** Clone current live definitions and balances without injecting configured/default currencies. */
    public BankBooks copyExact() {
        BankConfig current = new BankConfig(numeraire, feeFraction, interestFraction, personalInterestFraction,
                floatCentralRatio, pegCentralRatio, hardCentralRatio, clampMin, clampMax, 4.0,
                globalSidebar, globalBossbar, globalTab, globalHologram, joinMessage,
                new ArrayList<>(currencies.values()));
        return fromJson(toJson(), current);
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
        CurrencyDef legacyNote = defs.get("note");
        if (!root.has("currencyNameVersion") && legacyNote != null && ("银行券".equals(legacyNote.name()) || "中央银行券".equals(legacyNote.name()))) {
            defs.put("note", legacyNote.withName("央行券"));
        }
        BankBooks books = new BankBooks(defs);
        books.checkpoint = root.has("checkpoint") ? root.get("checkpoint").getAsLong() : 0;
        if (root.has("marketReceipts")) {
            root.getAsJsonObject("marketReceipts").entrySet().forEach(entry ->
                    books.marketReceipts.put(entry.getKey(), entry.getValue().getAsString()));
        }
        books.numeraire = root.has("numeraire") ? root.get("numeraire").getAsString() : fallback.numeraire();
        books.feeFraction = new BigDecimal(root.get("fee").getAsString());
        if (root.has("liveCopperPerDiamond") && !root.get("liveCopperPerDiamond").isJsonNull()) {
            books.liveCopperPerDiamond = new BigDecimal(root.get("liveCopperPerDiamond").getAsString());
        }
        if (root.has("liveNotesPerDiamond") && !root.get("liveNotesPerDiamond").isJsonNull()) {
            books.liveNotesPerDiamond = new BigDecimal(root.get("liveNotesPerDiamond").getAsString());
        }
        books.interestFraction = root.has("interestRate")
                ? new BigDecimal(root.get("interestRate").getAsString())
                : fallback.interestFraction();
        books.personalInterestFraction = root.has("personalInterestRate")
                ? new BigDecimal(root.get("personalInterestRate").getAsString())
                : fallback.personalInterestFraction();
        books.floatCentralRatio = root.has("floatCentralRatio")
                ? new BigDecimal(root.get("floatCentralRatio").getAsString()) : fallback.floatCentralReserve();
        books.pegCentralRatio = root.has("pegCentralRatio")
                ? new BigDecimal(root.get("pegCentralRatio").getAsString()) : fallback.pegCentralReserve();
        books.hardCentralRatio = root.has("hardCentralRatio")
                ? new BigDecimal(root.get("hardCentralRatio").getAsString()) : fallback.hardCentralReserve();
        if (root.has("centralReserveAccounts")) {
            root.getAsJsonObject("centralReserveAccounts").entrySet().forEach(entry ->
                    books.centralReserveAccounts.put(UUID.fromString(entry.getKey()), new BigInteger(entry.getValue().getAsString())));
        }
        books.lastInterestDay = root.has("lastInterestDay") ? root.get("lastInterestDay").getAsLong() : -1L;
        // Missing field intentionally means zero. Customer deposits must never be reclassified as equity.
        if (root.has("systemFundingLog")) {
            for (JsonElement entry : root.getAsJsonArray("systemFundingLog")) books.systemFundingLog.add(entry.deepCopy());
            while (books.systemFundingLog.size() > 200) books.systemFundingLog.remove(0);
        }
        if (root.has("centralTreasury")) {
            root.getAsJsonObject("centralTreasury").entrySet().forEach(entry -> {
                BigDecimal amount = Money.parse(entry.getValue().getAsString());
                requireStoredAmount(amount, false);
                books.treasury.put(entry.getKey(), amount);
            });
        }
        if (root.has("interestPool")) {
            root.getAsJsonObject("interestPool").entrySet()
                    .forEach(e -> books.interestPool.put(e.getKey(), Money.parse(e.getValue().getAsString())));
        }
        if (root.has("interestDust")) {
            root.getAsJsonObject("interestDust").entrySet()
                    .forEach(e -> books.interestDust.put(UUID.fromString(e.getKey()), new BigDecimal(e.getValue().getAsString())));
        }
        readAggregate(root, "shopOperating", books.shopOperating);
        readDust(root, "shopFraction", books.shopFraction);
        books.shopFraction.values().forEach(amount -> requireStoredAmount(amount, true));
        if (root.has("shopPersonal")) {
            root.getAsJsonObject("shopPersonal").entrySet().forEach(entry -> {
                BigDecimal amount = Money.parse(entry.getValue().getAsString());
                requireStoredAmount(amount, false);
                books.shopPersonal.put(UUID.fromString(entry.getKey()), amount);
            });
        }
        if (root.has("holdingFraction")) {
            root.getAsJsonObject("holdingFraction").entrySet().forEach(entry -> {
                Map<UUID, BigDecimal> row = new LinkedHashMap<>();
                entry.getValue().getAsJsonObject().entrySet().forEach(holder -> {
                    BigDecimal amount = Money.parse(holder.getValue().getAsString());
                    requireStoredAmount(amount, true);
                    row.put(UUID.fromString(holder.getKey()), amount);
                });
                books.holdingFraction.put(UUID.fromString(entry.getKey()), row);
            });
        }
        readAggregate(root, "landGrant", books.landGrant);
        readDust(root, "shopInterestDust", books.shopInterestDust);
        readDust(root, "landInterestDust", books.landInterestDust);
        if (root.has("personalInterestDust")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("personalInterestDust").entrySet()) {
                UUID noteId = UUID.fromString(entry.getKey());
                Map<UUID, BigDecimal> row = new LinkedHashMap<>();
                entry.getValue().getAsJsonObject().entrySet()
                        .forEach(e -> row.put(UUID.fromString(e.getKey()), new BigDecimal(e.getValue().getAsString())));
                if (!row.isEmpty()) {
                    books.personalInterestDust.put(noteId, row);
                }
            }
        }
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
                Supply supply = new Supply(entry.getKey(),
                        Money.parse(o.get("reserve").getAsString()),
                        Money.parse(o.get("liability").getAsString()),
                        Money.parse(o.get("anchor").getAsString()),
                        Money.parse(o.get("locked").getAsString()));
                if (o.has("depositVault")) {
                    supply.setDepositVault(Money.parse(o.get("depositVault").getAsString()));
                } else {
                    books.migrateVaults = true;
                }
                books.supplies.put(entry.getKey(), supply);
                books.currencies.putIfAbsent(entry.getKey(), CurrencyDef.orphan(entry.getKey()));
                books.alphas.putIfAbsent(entry.getKey(), new BigDecimal("1000"));
            }
        }
        for (CurrencyDef def : defs.values()) {
            books.supplies.putIfAbsent(def.id(), openingSupply(def));
        }
        if (root.has("accounts")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("accounts").entrySet()) {
                UUID id = UUID.fromString(entry.getKey());
                Map<String, BigDecimal> bals = new LinkedHashMap<>();
                entry.getValue().getAsJsonObject().entrySet()
                        .forEach(e -> bals.put(e.getKey(), Money.parse(e.getValue().getAsString())));
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
        if (root.has("issuerDebt")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("issuerDebt").entrySet()) {
                UUID id = UUID.fromString(entry.getKey());
                Map<String, BigInteger> row = new LinkedHashMap<>();
                entry.getValue().getAsJsonObject().entrySet()
                        .forEach(e -> row.put(e.getKey(), new BigInteger(e.getValue().getAsString())));
                if (!row.isEmpty()) {
                    books.issuerDebt.put(id, row);
                }
            }
        }
        if (root.has("personalNotes")) {
            for (JsonElement element : root.getAsJsonArray("personalNotes")) {
                JsonObject o = element.getAsJsonObject();
                PersonalNote note = o.has("regime")
                        ? new PersonalNote(
                                UUID.fromString(o.get("id").getAsString()),
                                UUID.fromString(o.get("issuer").getAsString()),
                                o.get("name").getAsString(),
                                NoteRegime.valueOf(o.get("regime").getAsString()),
                                o.get("anchor").getAsString(),
                                new BigInteger(o.get("par").getAsString()))
                        : new PersonalNote(
                                UUID.fromString(o.get("id").getAsString()),
                                UUID.fromString(o.get("issuer").getAsString()),
                                o.get("name").getAsString(),
                                o.get("copperPar").getAsBoolean(),
                                new BigInteger(o.get("par").getAsString()));
                note.setCirculation(new BigInteger(o.get("circulation").getAsString()));
                note.setBuys(new BigInteger(o.get("buys").getAsString()));
                note.setSells(new BigInteger(o.get("sells").getAsString()));
                note.setLocked(new BigInteger(o.get("locked").getAsString()));
                if (o.has("autoListing") && !o.get("autoListing").isJsonNull()) note.setAutoListing(o.get("autoListing").getAsBoolean());
                if (o.has("redemptionPaused") && !o.get("redemptionPaused").isJsonNull()) {
                    note.setRedemptionPaused(o.get("redemptionPaused").getAsBoolean());
                }
                if (o.has("openingDiamonds") && !o.get("openingDiamonds").isJsonNull()) {
                    note.adopt(note.regime(), note.anchor(), new BigDecimal(o.get("openingDiamonds").getAsString()));
                }
                if (o.has("interestPool")) {
                    note.addInterestPool(new BigInteger(o.get("interestPool").getAsString()));
                }
                if (o.has("interestDay")) {
                    note.setInterestDay(o.get("interestDay").getAsLong());
                }
                if (o.has("bankrupt") || o.has("drawn")) {
                    note.restoreClosure(o.has("bankrupt") && o.get("bankrupt").getAsBoolean(),
                            o.has("closedAt") ? o.get("closedAt").getAsLong() : 0L,
                            o.has("released") ? new BigInteger(o.get("released").getAsString()) : BigInteger.ZERO,
                            o.has("drawn") ? new BigInteger(o.get("drawn").getAsString()) : BigInteger.ZERO);
                }
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
        if (root.has("personalOffers")) {
            root.getAsJsonObject("personalOffers").entrySet().forEach(entry -> {
                UUID noteId = UUID.fromString(entry.getKey());
                if (books.personalNotes.containsKey(noteId)) {
                    entry.getValue().getAsJsonObject().entrySet().forEach(offer -> {
                        UUID holder = UUID.fromString(offer.getKey());
                        BigInteger amount = new BigInteger(offer.getValue().getAsString());
                        books.setPersonalOffer(noteId, holder, amount.min(books.personalHolding(noteId, holder)));
                    });
                }
            });
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
                books.outlets.get(books.outlets.size() - 1).setQuarterTurns(o.has("quarterTurns") ? o.get("quarterTurns").getAsInt() : 0);
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
        if (books.migrateVaults) {
            books.migrateMixedVaults();
        }
        books.fillMissingQuotes();
        if (root.has("recursiveClearing")) {
            JsonObject c = root.getAsJsonObject("recursiveClearing");
            if (c.has("landGrantFraction")) c.getAsJsonObject("landGrantFraction").entrySet().forEach(e -> books.landGrantFraction.put(e.getKey(), Money.parse(e.getValue().getAsString())));
            c.getAsJsonObject("reserveDust").entrySet().forEach(e -> books.reserveDust.put(UUID.fromString(e.getKey()), Money.parse(e.getValue().getAsString())));
            c.getAsJsonObject("recovered").entrySet().forEach(e -> books.recoveredNotes.put(UUID.fromString(e.getKey()), Money.parse(e.getValue().getAsString())));
            c.getAsJsonArray("switching").forEach(e -> books.reserveSwitching.add(UUID.fromString(e.getAsString())));
            c.getAsJsonObject("policies").entrySet().forEach(e -> { JsonObject row = e.getValue().getAsJsonObject(); books.policies.put(UUID.fromString(e.getKey()), new PersonalPolicy(row.get("target").getAsBigDecimal(), row.get("lower").getAsBigDecimal(), row.get("upper").getAsBigDecimal())); });
            c.getAsJsonObject("assets").entrySet().forEach(e -> { Map<String, BigDecimal> row = new LinkedHashMap<>(); e.getValue().getAsJsonObject().entrySet().forEach(a -> row.put(a.getKey(), Money.parse(a.getValue().getAsString()))); books.settledAssets.put(e.getKey(), row); });
            c.getAsJsonObject("receipts").entrySet().forEach(e -> books.clearingReceipts.put(e.getKey(), e.getValue().getAsJsonObject().deepCopy()));
        }
        // Old versions issued notes without assigning an owner. Allocate only the exact
        // remainder AFTER loading every claim (including recursive clearing and paper).
        // The marker prevents copyExact and later loads from reclassifying newer funds.
        if (!root.has("centralIssuanceTreasuryVersion")) {
            for (CurrencyDef def : books.currencies.values()) {
                if (!books.centralNote(def)) continue;
                BigDecimal remainder = books.supply(def.id()).liabilityExact()
                        .subtract(books.publicM1Exact(def.id())).subtract(books.treasuryExact(def.id()));
                if (remainder.signum() > 0) books.treasury.put(def.id(),
                        Money.of(books.treasuryExact(def.id()).add(remainder)));
            }
        }
        if (!root.has("feeTreasuryVersion")) {
            // Legacy central fee balances were separate owned assets. Personal-note
            // issuer interest pools are deliberately untouched. Unknown currencies stay put.
            for (Map.Entry<String, BigDecimal> entry : books.interestPool.entrySet()) {
                CurrencyDef def = books.need(entry.getKey());
                BigDecimal amount = entry.getValue();
                if (def == null || amount.signum() <= 0) continue;
                books.treasury.put(def.id(), Money.of(books.treasuryExact(def.id()).add(amount)));
                if (def.kind() != CurrencyKind.COMMODITY)
                    books.supply(def.id()).addLiability(amount);
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

    /** Savings-book total: personal balances, the shop, and the land-grant account. Not the vault. */
    public BigDecimal savingsBook(String currency) {
        return bookSavingsExact(currency);
    }

    /** Personal savings claims plus the shop operating account and the land-grant account. */
    private BigInteger bookSavings(String currency) {
        return Money.units(bookSavingsExact(currency));
    }

    private BigDecimal bookSavingsExact(String currency) {
        return Money.of(claimsExact(currency)
                .add(settledAssets.values().stream().map(row -> row.getOrDefault(currency, Money.ZERO)).reduce(Money.ZERO, BigDecimal::add))
                .add(shopExact(currency))
                .add(landGrantExact(currency))
                .add("note".equals(currency) ? pendingIssuerNotes() : Money.ZERO));
    }

    private BigDecimal claimsExact(String currency) {
        BigDecimal sum = Money.ZERO;
        for (Map<String, BigDecimal> bals : accounts.values()) {
            sum = sum.add(bals.getOrDefault(currency, Money.ZERO));
        }
        for (CheckRecord record : checks.all()) {
            if (record.status() == dev.jdguo.bank.instruments.CheckStatus.OUTSTANDING
                    && currency.equals(record.currency())) {
                sum = sum.add(Money.of(record.amount()));
            }
        }
        return Money.of(sum);
    }

    private BigDecimal publicM1Exact(String currency) {
        return Money.of(bookSavingsExact(currency).add(Money.of(paperValue(currency))));
    }

    private static BigInteger aggregate(Map<String, BigInteger> book, String currency) {
        if (currency == null) {
            return BigInteger.ZERO;
        }
        return book.getOrDefault(currency, BigInteger.ZERO);
    }

    private static void addAggregate(Map<String, BigInteger> book, String currency, BigInteger delta) {
        BigInteger next = aggregate(book, currency).add(delta);
        if (next.signum() < 0) {
            throw new IllegalStateException("sub-account underflow");
        }
        if (next.signum() == 0) {
            book.remove(currency);
        } else {
            book.put(currency, next);
        }
    }

    private static void requireStoredAmount(BigDecimal amount, boolean fraction) {
        if (amount.signum() < 0 || (fraction && amount.compareTo(BigDecimal.ONE) >= 0)) {
            throw new IllegalArgumentException("Invalid persisted settlement amount");
        }
    }

    private static void writeAggregate(JsonObject root, String name, Map<String, BigInteger> book) {
        JsonObject o = new JsonObject();
        book.forEach((id, amount) -> {
            if (amount.signum() > 0) {
                o.addProperty(id, amount.toString());
            }
        });
        if (o.size() > 0) {
            root.add(name, o);
        }
    }

    private static void writeDust(JsonObject root, String name, Map<String, BigDecimal> dust) {
        JsonObject o = new JsonObject();
        dust.forEach((id, amount) -> {
            if (amount.signum() != 0) {
                o.addProperty(id, amount.toPlainString());
            }
        });
        if (o.size() > 0) {
            root.add(name, o);
        }
    }

    private static void readAggregate(JsonObject root, String name, Map<String, BigInteger> into) {
        if (!root.has(name) || !root.get(name).isJsonObject()) {
            return;
        }
        root.getAsJsonObject(name).entrySet().forEach(entry -> {
            BigInteger amount = new BigInteger(entry.getValue().getAsString());
            if (amount.signum() > 0) {
                into.put(entry.getKey(), amount);
            }
        });
    }

    private static void readDust(JsonObject root, String name, Map<String, BigDecimal> into) {
        if (!root.has(name) || !root.get(name).isJsonObject()) {
            return;
        }
        root.getAsJsonObject(name).entrySet().forEach(entry -> {
            BigDecimal amount = new BigDecimal(entry.getValue().getAsString());
            if (amount.signum() != 0) {
                into.put(entry.getKey(), amount);
            }
        });
    }

    private Map<String, BigInteger> claimMap() {
        Map<String, BigInteger> claims = new LinkedHashMap<>();
        for (Map<String, BigDecimal> bals : accounts.values()) {
            bals.forEach((c, v) -> claims.merge(c, Money.units(v), BigInteger::add));
        }
        for (CheckRecord record : checks.all()) {
            if (record.status() == CheckStatus.OUTSTANDING) {
                claims.merge(record.currency(), record.amount(), BigInteger::add);
            }
        }
        return claims;
    }

    private BigDecimal holdingTotalExact(UUID noteId) {
        BigDecimal sum = shopPersonal.getOrDefault(noteId, Money.ZERO).add(personalRecovered(noteId));
        for (JsonObject receipt : clearingReceipts.values()) {
            if (deliveryPending(receipt) && receipt.has("kind") && "issuer-offer-payout".equals(receipt.get("kind").getAsString())
                    && noteId.toString().equals(receipt.get("noteId").getAsString())) sum = sum.add(Money.parse(receipt.get("privateAmount").getAsString()));
        }
        for (PersonalNote collateralOwner : personalNotes.values()) {
            if (("personal:" + noteId).equals(collateralOwner.anchor())) sum = sum.add(effectiveBackingExact(collateralOwner));
        }
        Map<UUID, BigInteger> row = personalHoldings.get(noteId);
        if (row != null) {
            for (UUID holder : row.keySet()) {
                sum = sum.add(personalHoldingExact(noteId, holder));
            }
        }
        Map<UUID, BigDecimal> fractions = holdingFraction.get(noteId);
        if (fractions != null) {
            for (UUID holder : fractions.keySet()) {
                if (row == null || !row.containsKey(holder)) {
                    sum = sum.add(personalHoldingExact(noteId, holder));
                }
            }
        }
        return Money.of(sum);
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
        creditBalance(player, currency, Money.of(amount));
    }

    private void debitBalance(UUID player, String currency, BigInteger amount) {
        debitBalance(player, currency, Money.of(amount));
    }

    private void creditBalance(UUID player, String currency, BigDecimal amount) {
        BigDecimal next = Money.of(balanceExact(player, currency).add(Money.of(amount)));
        Map<String, BigDecimal> map = accounts.computeIfAbsent(player, id -> new LinkedHashMap<>());
        if (next.signum() == 0) {
            map.remove(currency);
        } else {
            map.put(currency, next);
        }
    }

    private void debitBalance(UUID player, String currency, BigDecimal amount) {
        BigDecimal next = Money.of(balanceExact(player, currency).subtract(Money.of(amount)));
        if (next.signum() < 0) {
            throw new IllegalStateException("balance underflow");
        }
        Map<String, BigDecimal> map = accounts.computeIfAbsent(player, id -> new LinkedHashMap<>());
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

    /** One player's credited demand-deposit interest for the days just settled. */
    public record InterestCredit(UUID player, BigInteger amount, UUID personalNote) {}

    /** Whether the day clock moved, and the credits that were actually paid from the pool. */
    public record InterestDay(boolean moved, List<InterestCredit> credits) {}
}
