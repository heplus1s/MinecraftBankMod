package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text drawn above the bank screen. Money is shown to 6 decimal places.
 * The exchange rate stays. The normal redemption status stays.
 * Extra redemption lines, such as a separate capacity ratio, are left out.
 */
public final class BankHeader {
    private static final Set<String> NORMAL_STATUS = Set.of(
            "兑付正常", "暂停兑付", "无法兑付", "准备金不足", "准备金紧张");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private BankHeader() {
    }

    public static List<String> lines(JsonObject data) {
        List<String> lines = new ArrayList<>();
        lines.add(rateLine(centralRateRaw(data)));
        String status = text(data, "status");
        if (showStatus(status)) {
            lines.add(status);
        }
        return lines;
    }

    /** M0, M1, and M2 belong only on the central-bank management page. Integers drop a trailing decimal run. */
    public static List<String> managementAggregates(JsonObject data) {
        return List.of(
                "流通中现金（M0）：" + figure(text(data, "m0")),
                "狭义货币（M1）：" + figure(text(data, "m1")),
                "广义货币（M2）：" + figure(text(data, "m2")));
    }

    /**
     * Money to four decimal places. A whole number, including zero, drops the decimal run,
     * so {@code 0} stays {@code 0} and {@code 0.2500} stays {@code 0.2500}.
     */
    public static String figure(String raw) {
        if (raw == null || raw.isBlank()) {
            return "暂无数据";
        }
        java.math.BigDecimal parsed = BankRates.parse(raw);
        if (parsed == null) {
            return raw.trim();
        }
        java.math.BigDecimal scaled = parsed.setScale(4, java.math.RoundingMode.HALF_UP);
        java.math.BigDecimal whole = scaled.stripTrailingZeros();
        if (whole.scale() <= 0) {
            return whole.setScale(0).toPlainString();
        }
        return scaled.toPlainString();
    }

    public static String centralRateRaw(JsonObject data) {
        return rateValue(data);
    }

    /**
     * Cleans lines copied from the chest header. Keeps the exchange rate and the
     * normal redemption status. Drops capacity ratios and duplicate redemption lines.
     */
    public static List<String> keepChestHeader(List<String> ported) {
        String rate = null;
        String status = null;
        if (ported != null) {
            for (String raw : ported) {
                if (raw == null) {
                    continue;
                }
                String line = raw.trim();
                if (line.contains("汇率") && rate == null) {
                    rate = normalizeRate(line);
                } else if (isNormalStatus(line) && status == null) {
                    status = line;
                }
            }
        }
        List<String> kept = new ArrayList<>();
        kept.add(rate == null ? "汇率  暂无牌价" : rate);
        if (status != null) {
            kept.add(status);
        }
        return kept;
    }

    public static String fixed6(String raw) {
        if (raw == null || raw.isBlank() || "—".equals(raw.trim()) || "-".equals(raw.trim())) {
            return "—";
        }
        try {
            String cleaned = raw.trim().replace(",", "").replace("_", "");
            return new BigDecimal(cleaned).setScale(6, RoundingMode.HALF_UP).toPlainString();
        } catch (NumberFormatException exception) {
            return "—";
        }
    }

    public static boolean showStatus(String status) {
        if (status == null || status.isBlank() || "-".equals(status) || "—".equals(status)) {
            return false;
        }
        if (status.contains("兑付能力")) {
            return false;
        }
        if (status.startsWith("兑付：") || status.startsWith("兑付:")) {
            return false;
        }
        String lower = status.toLowerCase();
        return !lower.contains("redemption");
    }

    static boolean isNormalStatus(String line) {
        return NORMAL_STATUS.contains(line);
    }

    private static String rateLine(String value) {
        String shown = fixed6(value);
        if ("—".equals(shown)) {
            return "汇率  暂无牌价";
        }
        return "汇率  " + shown + " 铜/钻";
    }

    private static String normalizeRate(String line) {
        Matcher matcher = NUMBER.matcher(line);
        if (!matcher.find()) {
            return "汇率  暂无牌价";
        }
        return rateLine(matcher.group());
    }

    private static String rateValue(JsonObject data) {
        for (String key : new String[] {"copperPerDiamond", "rate", "exchangeRate"}) {
            if (data != null && data.has(key) && data.get(key).isJsonPrimitive()) {
                return data.get(key).getAsString();
            }
        }
        return null;
    }

    private static String text(JsonObject data, String key) {
        if (data == null || !data.has(key) || data.get(key).isJsonNull()) {
            return "";
        }
        return data.get(key).getAsString();
    }
}
