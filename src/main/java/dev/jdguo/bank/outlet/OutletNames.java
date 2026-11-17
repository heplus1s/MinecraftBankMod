package dev.jdguo.bank.outlet;

/** Unicode names are kept verbatim; no lossy ASCII conversion or ID renaming. */
public final class OutletNames {
    private OutletNames() {}
    public static String commandName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\""))
            name = name.substring(1, name.length() - 1);
        return name;
    }
    public static boolean valid(String name) {
        if (name == null || name.isBlank() || !name.equals(name.strip())
                || name.codePointCount(0, name.length()) > 32) return false;
        return name.codePoints().allMatch(c -> Character.isLetterOrDigit(c)
                || c == ' ' || c == '_' || c == '-');
    }
}
