package dev.jdguo.bank.currency;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** The on-disk seed file. Live balances are not stored here. */
public final class ConfigJson {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ConfigJson() {}

    public static String write(BankConfig config) {
        JsonObject root = new JsonObject();
        root.addProperty("numeraire", config.numeraire());
        root.addProperty("feePercent", config.feeFraction().multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString());
        root.addProperty("interestPercent", config.interestFraction().multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString());
        root.add("clampMin", config.clampMin() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(config.clampMin().toPlainString()));
        root.add("clampMax", config.clampMax() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(config.clampMax().toPlainString()));
        root.addProperty("outletRadius", config.outletRadius());
        JsonObject displays = new JsonObject();
        displays.addProperty("sidebar", config.sidebar());
        displays.addProperty("bossbar", config.bossbar());
        displays.addProperty("tab", config.tab());
        displays.addProperty("hologram", config.hologram());
        displays.addProperty("joinMessage", config.joinMessage());
        root.add("displays", displays);
        JsonArray currencies = new JsonArray();
        for (CurrencyDef def : config.currencies()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", def.id());
            o.addProperty("kind", def.kind().name().toLowerCase());
            o.addProperty("item", def.itemId());
            o.addProperty("name", def.name());
            o.addProperty("nameEn", def.nameEn());
            o.addProperty("color", def.color());
            o.addProperty("initialReserve", def.initialReserve().toString());
            o.addProperty("initialLiability", def.initialLiability().toString());
            o.addProperty("alphaPermille", def.alphaPermille().toPlainString());
            o.addProperty("issuable", def.issuable());
            if (def.backing() != null) {
                o.addProperty("backing", def.backing());
                o.addProperty("parPerBacking", def.parPerBacking().toPlainString());
                o.addProperty("haircut", def.haircut().toPlainString());
                o.addProperty("minReserveRatio", def.minReserveRatio().toPlainString());
            }
            currencies.add(o);
        }
        root.add("currencies", currencies);
        return GSON.toJson(root);
    }

    public static BankConfig parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        BankConfig defaults = BankConfig.defaults();
        String numeraire = text(root, "numeraire", defaults.numeraire());
        BigDecimal fee = root.has("feePercent")
                ? decimal(root.get("feePercent")).divide(new BigDecimal("100"))
                : defaults.feeFraction();
        BigDecimal interest = root.has("interestPercent")
                ? decimal(root.get("interestPercent")).divide(new BigDecimal("100"))
                : defaults.interestFraction();
        BigDecimal clampMin = optionalDecimal(root, "clampMin");
        BigDecimal clampMax = optionalDecimal(root, "clampMax");
        double radius = root.has("outletRadius") ? root.get("outletRadius").getAsDouble() : defaults.outletRadius();
        boolean sidebar = defaults.sidebar();
        boolean bossbar = defaults.bossbar();
        boolean tab = defaults.tab();
        boolean hologram = defaults.hologram();
        boolean join = defaults.joinMessage();
        if (root.has("displays")) {
            JsonObject displays = root.getAsJsonObject("displays");
            sidebar = bool(displays, "sidebar", sidebar);
            bossbar = bool(displays, "bossbar", bossbar);
            tab = bool(displays, "tab", tab);
            hologram = bool(displays, "hologram", hologram);
            join = bool(displays, "joinMessage", join);
        }
        List<CurrencyDef> currencies = new ArrayList<>();
        if (root.has("currencies")) {
            for (JsonElement element : root.getAsJsonArray("currencies")) {
                currencies.add(currency(element.getAsJsonObject()));
            }
        }
        if (currencies.isEmpty()) {
            currencies = defaults.currencies();
        }
        BankConfig config = new BankConfig(numeraire, fee, interest, clampMin, clampMax, radius,
                sidebar, bossbar, tab, hologram, join, currencies);
        config.validate();
        return config;
    }

    private static CurrencyDef currency(JsonObject o) {
        String id = o.get("id").getAsString();
        CurrencyKind kind = CurrencyKind.valueOf(o.get("kind").getAsString().trim().toUpperCase());
        BigDecimal alpha = o.has("alphaPermille") ? decimal(o.get("alphaPermille")) : new BigDecimal("1000");
        BigInteger reserve = o.has("initialReserve") ? integer(o.get("initialReserve")) : BigInteger.ZERO;
        BigInteger liability = o.has("initialLiability") ? integer(o.get("initialLiability")) : reserve;
        boolean issuable = o.has("issuable") && o.get("issuable").getAsBoolean();
        String backing = o.has("backing") && !o.get("backing").isJsonNull() ? o.get("backing").getAsString() : null;
        return new CurrencyDef(
                id,
                kind,
                text(o, "item", "minecraft:paper"),
                text(o, "name", id),
                text(o, "nameEn", id),
                text(o, "color", "white"),
                reserve,
                liability,
                alpha,
                issuable,
                backing,
                o.has("parPerBacking") ? decimal(o.get("parPerBacking")) : null,
                o.has("haircut") ? decimal(o.get("haircut")) : null,
                o.has("minReserveRatio") ? decimal(o.get("minReserveRatio")) : null);
    }

    private static String text(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        return o.has(key) ? o.get(key).getAsBoolean() : fallback;
    }

    private static BigDecimal optionalDecimal(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        String raw = o.get(key).getAsString().trim();
        if (raw.isEmpty() || raw.equalsIgnoreCase("off") || raw.equalsIgnoreCase("null")) {
            return null;
        }
        return new BigDecimal(raw);
    }

    private static BigDecimal decimal(JsonElement element) {
        return new BigDecimal(element.getAsString());
    }

    private static BigInteger integer(JsonElement element) {
        String raw = element.getAsString().trim();
        if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
            return new BigDecimal(raw).toBigInteger();
        }
        return new BigInteger(raw);
    }
}
