package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Left-column quotes. Copper per diamond is shown as written.
 * Notes per diamond are inverted so the line is diamonds for one bank note.
 */
public final class BankRates {
    public static final String UNAVAILABLE = "暂无牌价";

    private BankRates() {
    }

    public static List<String> column(JsonObject data) {
        List<String> lines = new ArrayList<>();
        String status = BankSnapshot.text(data, "status");
        if (BankHeader.showStatus(status)) {
            lines.add(status);
        }
        lines.add(centralQuote(data));
        lines.add(bankNoteQuote(data));
        for (BankSnapshot.NoteRow note : BankSnapshot.notes(data)) {
            lines.add(personalQuote(note));
        }
        return lines;
    }

    /** {@code copperPerDiamond} is copper ingots for one diamond. */
    public static String centralQuote(JsonObject data) {
        String shown = amount(BankHeader.centralRateRaw(data));
        if (UNAVAILABLE.equals(shown)) {
            return "1 钻石 = " + UNAVAILABLE;
        }
        return "1 钻石 = " + shown + " 铜锭";
    }

    /**
     * {@code unitsPerDiamond} or {@code rate} on the central note is notes for one diamond.
     * The displayed line is diamonds for one bank note.
     */
    public static String bankNoteQuote(JsonObject data) {
        String direct = amount(BankSnapshot.text(data, "diamondsPerNote"));
        if (!UNAVAILABLE.equals(direct)) {
            return "1 银行券 = " + direct + " 钻石";
        }
        BankSnapshot.MoneyRow note = centralNote(data);
        if (note == null) {
            return "1 银行券 = " + UNAVAILABLE;
        }
        String written = note.unitsPerDiamond() != null ? note.unitsPerDiamond() : note.rate();
        BigDecimal notes = parse(written);
        if (notes == null || notes.signum() == 0) {
            return "1 银行券 = " + UNAVAILABLE;
        }
        BigDecimal diamonds = BigDecimal.ONE.divide(notes, 6, RoundingMode.HALF_UP);
        return "1 银行券 = " + BankHeader.fixed6(diamonds.toPlainString()) + " 钻石";
    }

    /** {@code price} is central bank notes for one personal note. */
    public static String personalQuote(BankSnapshot.NoteRow note) {
        String name = note.name() == null ? "" : note.name();
        String shown = "0".equals(note.price()) ? "0" : amount(note.price());
        if (UNAVAILABLE.equals(shown)) {
            return "1 【" + name + "】 = " + UNAVAILABLE;
        }
        return "1 【" + name + "】 = " + shown + " 银行券";
    }

    private static BankSnapshot.MoneyRow centralNote(JsonObject data) {
        BankSnapshot.MoneyRow named = null;
        for (BankSnapshot.MoneyRow row : BankSnapshot.currencies(data)) {
            if ("note".equals(row.id())) {
                return row;
            }
            if ("银行券".equals(row.name())) {
                named = row;
            }
        }
        return named;
    }

    private static String amount(String raw) {
        if (raw == null || raw.isBlank() || UNAVAILABLE.equals(raw) || "无法报价".equals(raw)) {
            return UNAVAILABLE;
        }
        String scaled = BankHeader.fixed6(raw);
        return "—".equals(scaled) ? raw.trim() : scaled;
    }

    static BigDecimal parse(String raw) {
        if (raw == null || raw.isBlank() || UNAVAILABLE.equals(raw) || "无法报价".equals(raw) || "—".equals(raw)) {
            return null;
        }
        String value = raw.trim().replace(",", "").replace("_", "");
        BigDecimal multiplier = BigDecimal.ONE;
        char last = value.charAt(value.length() - 1);
        if (Character.isLetter(last)) {
            multiplier = switch (Character.toLowerCase(last)) {
                case 'k' -> new BigDecimal("1000");
                case 'm' -> new BigDecimal("1000000");
                default -> null;
            };
            if (multiplier == null) {
                return null;
            }
            value = value.substring(0, value.length() - 1);
        }
        try {
            return new BigDecimal(value).multiply(multiplier);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
