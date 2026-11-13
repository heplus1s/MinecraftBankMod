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
    private final BigDecimal clampMin;
    private final BigDecimal clampMax;
    private final double outletRadius;
    private final boolean sidebar;
    private final boolean bossbar;
    private final boolean tab;
    private final boolean hologram;
    private final boolean joinMessage;
    private final List<CurrencyDef> currencies;

    public BankConfig(String numeraire, BigDecimal feeFraction, BigDecimal clampMin, BigDecimal clampMax,
                      double outletRadius, boolean sidebar, boolean bossbar, boolean tab, boolean hologram,
                      boolean joinMessage, List<CurrencyDef> currencies) {
        this.numeraire = numeraire;
        this.feeFraction = feeFraction;
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
                "银票", "Silver Note", "yellow",
                new BigInteger("100000"), new BigInteger("100000"), alpha,
                true, "diamond", new BigDecimal("100"), BigDecimal.ONE, BigDecimal.ONE));
        return new BankConfig("diamond", new BigDecimal("0.02"), null, null, 4.0,
                true, true, true, true, true, list);
    }

    public void validate() {
        if (currencies.isEmpty()) {
            throw new IllegalArgumentException("至少需要一种货币");
        }
        Map<String, CurrencyDef> byId = new LinkedHashMap<>();
        Map<String, String> items = new LinkedHashMap<>();
        for (CurrencyDef c : currencies) {
            if (c.id() == null || !c.id().matches("[a-z0-9_]{1,32}")) {
                throw new IllegalArgumentException("货币 id 无效: " + c.id());
            }
            if (byId.put(c.id(), c) != null) {
                throw new IllegalArgumentException("货币 id 重复: " + c.id());
            }
            if (c.kind() == CurrencyKind.COMMODITY) {
                if (c.itemId() == null || c.itemId().isBlank()) {
                    throw new IllegalArgumentException(c.id() + " 缺少物品 id");
                }
                String prev = items.put(c.itemId(), c.id());
                if (prev != null) {
                    throw new IllegalArgumentException("物品 " + c.itemId() + " 同时绑定了 " + prev + " 和 " + c.id());
                }
            }
            if (c.alphaPermille().signum() < 0) {
                throw new IllegalArgumentException(c.id() + " 的 alpha 不能为负");
            }
        }
        if (!byId.containsKey(numeraire)) {
            throw new IllegalArgumentException("计价货币不存在: " + numeraire);
        }
        for (CurrencyDef c : currencies) {
            if (c.kind() != CurrencyKind.VIRTUAL) {
                continue;
            }
            if (c.backing() == null || !byId.containsKey(c.backing())) {
                throw new IllegalArgumentException(c.id() + " 需要存在的准备金货币 backing");
            }
            if (byId.get(c.backing()).kind() != CurrencyKind.COMMODITY) {
                throw new IllegalArgumentException(c.id() + " 的 backing 必须是实物货币");
            }
            if (c.parPerBacking() == null || c.parPerBacking().signum() <= 0) {
                throw new IllegalArgumentException(c.id() + " 的 parPerBacking 必须为正");
            }
            if (c.haircut() == null || c.haircut().signum() < 0 || c.haircut().compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(c.id() + " 的 haircut 必须在 0 到 1 之间");
            }
            if (c.minReserveRatio() == null || c.minReserveRatio().signum() <= 0) {
                throw new IllegalArgumentException(c.id() + " 的 minReserveRatio 必须为正");
            }
        }
        if (feeFraction.signum() < 0 || feeFraction.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("手续费必须在 0% 到 100% 之间");
        }
        if (outletRadius < 1 || outletRadius > 64) {
            throw new IllegalArgumentException("网点半径必须在 1 到 64 之间");
        }
    }

    public String numeraire() { return numeraire; }
    public BigDecimal feeFraction() { return feeFraction; }
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
