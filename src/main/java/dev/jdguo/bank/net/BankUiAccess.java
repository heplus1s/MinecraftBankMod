package dev.jdguo.bank.net;

/** Management routing is never inferred solely from client capability. */
public final class BankUiAccess {
    private BankUiAccess() {}
    public static boolean sessionAllowed(boolean adminSession, boolean operator) {
        return !adminSession || operator;
    }

    public static boolean management(boolean remoteAdmin, boolean managementPage, boolean operator) {
        return operator && (remoteAdmin || managementPage);
    }
}
