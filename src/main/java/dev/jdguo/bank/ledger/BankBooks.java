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
import dev.jdguo.bank.instruments.PresentedCheck;
import dev.jdguo.bank.outlet.OutletPoint;

import java.math.BigDecimal;
import java.math.BigInteger;
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
    private final CheckBook checks = new CheckBook();
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
            return TxResult.fail("not_commodity", "Notes can't be deposited as items. Redeem a bill instead.");
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
            return TxResult.fail("not_commodity", "Notes have no physical item. Issue a bill or exchange them.");
        }
        if (amount == null || amount.signum() <= 0) {
            return TxResult.fail("bad_amount", "Invalid amount");
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
        if (recv.kind() == CurrencyKind.COMMODITY) {
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
        creditBalance(player, receiveId, receiveQty);
        supply(receiveId).addLiability(receiveQty);
        return TxResult.ok(cost, "Exchanged " + CompactFormat.format(cost) + " " + label(pay)
                + " for " + CompactFormat.format(receiveQty) + " " + label(recv));
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
            return frozen ? "停止兑现" : "兑现正常";
        }
        if (abundance(note.id()) == null) {
            return "金圆券 · 挤兑";
        }
        if (frozen) {
            return "停止兑现";
        }
        BigDecimal tight = tightness(note.id());
        if (tight == null) {
            return "兑现正常";
        }
        if (tight.compareTo(new BigDecimal("0.05")) < 0) {
            return "金圆券 · 挤兑";
        }
        if (tight.compareTo(new BigDecimal("0.25")) < 0) {
            return "准备金告急";
        }
        if (tight.compareTo(new BigDecimal("0.80")) < 0) {
            return "准备金偏紧";
        }
        return "兑现正常";
    }

    /** English status for {@code /bank supply}. Displays keep {@link #statusLabel()}. */
    public String statusLabelEn() {
        CurrencyDef note = primaryVirtual();
        if (note == null) {
            return frozen ? "redemption halted" : "redemption open";
        }
        if (abundance(note.id()) == null) {
            return "bank run";
        }
        if (frozen) {
            return "redemption halted";
        }
        BigDecimal tight = tightness(note.id());
        if (tight == null) {
            return "redemption open";
        }
        if (tight.compareTo(new BigDecimal("0.05")) < 0) {
            return "bank run";
        }
        if (tight.compareTo(new BigDecimal("0.25")) < 0) {
            return "reserves critical";
        }
        if (tight.compareTo(new BigDecimal("0.80")) < 0) {
            return "reserves tight";
        }
        return "redemption open";
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

    public void setReserve(String currency, BigInteger reserve) {
        if (reserve.signum() < 0) {
            throw new IllegalArgumentException("Reserve can't be negative");
        }
        Supply self = supply(currency);
        if (reserve.compareTo(self.locked()) < 0) {
            throw new IllegalArgumentException("Reserve can't be less than the locked amount");
        }
        self.setReserve(reserve);
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
                        nullableUuid(o, "hologramId")));
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
        return claims;
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
