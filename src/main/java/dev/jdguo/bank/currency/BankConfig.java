package dev.jdguo.bank.currency;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seed configuration. After the first world save, live policy lives in the ledger snapshot.
 * Defaults follow the datapack: 2% fee, alpha 1000‰, copper 1_000_000, diamond 1_000.
 * Rate clamps are off so a paper currency can hyperinflate.
 */
public final class BankConfig {
    private final String numeraire;
    private final BigDecimal feeFraction;
    private final BigDecimal interestFraction;
    private final BigDecimal personalInterestFraction;
    private final BigDecimal floatCentralReserve;
    private final BigDecimal pegCentralReserve;
    private final BigDecimal hardCentralReserve;
    private final BigDecimal clampMin;
    private final BigDecimal clampMax;
    private final double outletRadius;
    private final boolean sidebar;
    private final boolean bossbar;
    private final boolean tab;
    private final boolean hologram;
    private final boolean joinMessage;
    private final List<CurrencyDef> currencies;

    public BankConfig(String numeraire, BigDecimal feeFraction, BigDecimal interestFraction,
                      BigDecimal personalInterestFraction, BigDecimal floatCentralReserve,
                      BigDecimal pegCentralReserve, BigDecimal hardCentralReserve,
                      BigDecimal clampMin, BigDecimal clampMax,
                      double outletRadius, boolean sidebar, boolean bossbar, boolean tab, boolean hologram,
                      boolean joinMessage, List<CurrencyDef> currencies) {
        this.numeraire = numeraire;
        this.feeFraction = feeFraction;
        this.interestFraction = interestFraction == null ? new BigDecimal("0.02") : interestFraction;
        this.personalInterestFraction = personalInterestFraction == null ? BigDecimal.ZERO : personalInterestFraction;
        this.floatCentralReserve = floatCentralReserve == null ? new BigDecimal("0.10") : floatCentralReserve;
        this.pegCentralReserve = pegCentralReserve == null ? new BigDecimal("0.20") : pegCentralReserve;
        this.hardCentralReserve = hardCentralReserve == null ? new BigDecimal("0.05") : hardCentralReserve;
        this.clampMin = clampMin;
        this.clampMax = clampMax;
        this.outletRadius = outletRadius;
        this.sidebar = sidebar;
        this.bossbar = bossbar;
        this.tab = tab;
        this.hologram = hologram;
        this.joinMessage = joinMessage;
        this.currencies = List.copyOf(currencies);
    }

    public static BankConfig defaults() {
        BigDecimal alpha = new BigDecimal("1000");
        List<CurrencyDef> list = new ArrayList<>();
        list.add(new CurrencyDef(
                "copper", CurrencyKind.COMMODITY, "minecraft:copper_ingot",
                "铜锭", "Copper", "gold",
                new BigInteger("1000000"), new BigInteger("1000000"), alpha,
                false, null, null, null, null));
        list.add(new CurrencyDef(
                "diamond", CurrencyKind.COMMODITY, "minecraft:diamond",
                "钻石", "Diamond", "aqua",
                new BigInteger("1000"), new BigInteger("1000"), alpha,
                false, null, null, null, null));
        list.add(new CurrencyDef(
                "note", CurrencyKind.VIRTUAL, "minecraft:paper",
                "银行券", "bank note", "yellow",
                new BigInteger("1000"), new BigInteger("1000"), alpha,
                true, "diamond", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        return new BankConfig("diamond", new BigDecimal("0.02"), new BigDecimal("0.02"), BigDecimal.ZERO,
                new BigDecimal("0.10"), new BigDecimal("0.20"), new BigDecimal("0.05"), null, null, 4.0,
                true, true, true, true, true, list);
    }

    public void validate() {
        if (currencies.isEmpty()) {
            throw new IllegalArgumentException("Need at least one currency");
        }
        Map<String, CurrencyDef> byId = new LinkedHashMap<>();
        Map<String, String> items = new LinkedHashMap<>();
        for (CurrencyDef c : currencies) {
            if (c.id() == null || !c.id().matches("[a-z0-9_]{1,32}")) {
                throw new IllegalArgumentException("Invalid currency id: " + c.id());
            }
            if (byId.put(c.id(), c) != null) {
                throw new IllegalArgumentException("Duplicate currency id: " + c.id());
            }
            if (c.kind() == CurrencyKind.COMMODITY) {
                if (c.itemId() == null || c.itemId().isBlank()) {
                    throw new IllegalArgumentException(c.id() + " is missing an item id");
                }
                String prev = items.put(c.itemId(), c.id());
                if (prev != null) {
                    throw new IllegalArgumentException("Item " + c.itemId() + " is bound to both " + prev + " and " + c.id());
                }
            }
            if (c.alphaPermille().signum() < 0) {
                throw new IllegalArgumentException(c.id() + " alpha can't be negative");
            }
        }
        if (!byId.containsKey(numeraire)) {
            throw new IllegalArgumentException("Numeraire does not exist: " + numeraire);
        }
        for (CurrencyDef c : currencies) {
            if (c.kind() != CurrencyKind.VIRTUAL) {
                continue;
            }
            if (c.backing() == null || !byId.containsKey(c.backing())) {
                throw new IllegalArgumentException(c.id() + " needs a backing currency that exists");
            }
            if (byId.get(c.backing()).kind() != CurrencyKind.COMMODITY) {
                throw new IllegalArgumentException(c.id() + " backing must be a commodity");
            }
            if (c.parPerBacking() == null || c.parPerBacking().signum() <= 0) {
                throw new IllegalArgumentException(c.id() + " parPerBacking must be positive");
            }
            if (c.haircut() == null || c.haircut().signum() < 0 || c.haircut().compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(c.id() + " haircut must be between 0 and 1");
            }
            if (c.minReserveRatio() == null || c.minReserveRatio().signum() <= 0) {
                throw new IllegalArgumentException(c.id() + " minReserveRatio must be positive");
            }
        }
        if (feeFraction.signum() < 0 || feeFraction.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Fee must be between 0% and 100%");
        }
        if (interestFraction.signum() < 0 || interestFraction.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Interest must be between 0% and 100%");
        }
        if (personalInterestFraction.signum() < 0 || personalInterestFraction.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Personal-note interest must be between 0% and 100%");
        }
        if (badRatio(floatCentralReserve) || badRatio(pegCentralReserve) || badRatio(hardCentralReserve)) {
            throw new IllegalArgumentException("Central reserve ratios must be between 0% and 100%");
        }
        if (outletRadius < 1 || outletRadius > 64) {
            throw new IllegalArgumentException("Outlet radius must be between 1 and 64");
        }
    }

    public String numeraire() { return numeraire; }
    public BigDecimal feeFraction() { return feeFraction; }
    public BigDecimal interestFraction() { return interestFraction; }
    public BigDecimal personalInterestFraction() { return personalInterestFraction; }
    public BigDecimal floatCentralReserve() { return floatCentralReserve; }
    public BigDecimal pegCentralReserve() { return pegCentralReserve; }
    public BigDecimal hardCentralReserve() { return hardCentralReserve; }

    private static boolean badRatio(BigDecimal ratio) {
        return ratio.signum() < 0 || ratio.compareTo(BigDecimal.ONE) > 0;
    }
    public BigDecimal clampMin() { return clampMin; }
    public BigDecimal clampMax() { return clampMax; }
    public double outletRadius() { return outletRadius; }
    public boolean sidebar() { return sidebar; }
    public boolean bossbar() { return bossbar; }
    public boolean tab() { return tab; }
    public boolean hologram() { return hologram; }
    public boolean joinMessage() { return joinMessage; }
    public List<CurrencyDef> currencies() { return currencies; }

    public CurrencyDef currency(String id) {
        for (CurrencyDef c : currencies) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    public Map<String, CurrencyDef> asMap() {
        Map<String, CurrencyDef> map = new LinkedHashMap<>();
        for (CurrencyDef c : currencies) {
            map.put(c.id(), c);
        }
        return Collections.unmodifiableMap(map);
    }
}
