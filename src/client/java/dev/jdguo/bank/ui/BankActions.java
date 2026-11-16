package dev.jdguo.bank.ui;

/**
 * Payloads the installed bank core already accepts. Nothing here is a new server action.
 * Naming a personal note is a chat command, not a text field: the core's command is
 * {@code /bank note create <float|peg|hard> }, and the player types the name after it.
 */
public final class BankActions {
    private BankActions() {
    }

    public static String deposit(String currencyId, String quantity) {
        return "deposit-transfer " + currencyId + " " + quantity;
    }

    public static String withdraw(String currencyId, String quantity) {
        return "withdraw " + currencyId + " " + quantity;
    }

    public static String exchange(String payId, String receiveId, String quantity) {
        return "exchange " + payId + " " + receiveId + " " + quantity;
    }

    public static String check(String currencyId, String quantity, boolean selfOnly) {
        return "check " + currencyId + " " + quantity + (selfOnly ? " self" : " bearer");
    }

    public static final String MAIN_HAND_BILL = "兑付手里拿着的那一张汇票。请把要兑付的汇票拿在主手。一次只兑付这一张，不会扫描背包，也不会存入铜锭、钻石或银行券。";
    public static final String MAIN_HAND_BUTTON = "确认兑付手里拿着的那一张汇票";

    /** Redeems the bill in the main hand. The core no longer scans the backpack. */
    public static String presentHeld() {
        return "deposit-held";
    }

    public static String noteBuy(String name, String quantity) {
        return "note-buy " + name + " " + quantity;
    }

    public static String noteSell(String name, String quantity) {
        return "note-sell " + name + " " + quantity;
    }

    public static String noteSwap(String payName, String receiveName, String quantity) {
        return "note-swap " + payName + " " + receiveName + " " + quantity;
    }

    public static String noteRedeem(String name, String payoutId, String quantity) {
        return "note-redeem " + name + " " + payoutId + " " + quantity;
    }

    public static String noteIssue(String quantity) {
        return "note-issue " + quantity;
    }

    public static String noteUnbacked(String quantity) {
        return "note-unbacked " + quantity;
    }

    /** Issues the typed amount with no reserve lock. */
    public static String issueUnbacked(String quantity) {
        return "issue-unbacked " + quantity;
    }

    /** Retires the typed amount of the player's own note. */
    public static String noteRetire(String quantity) {
        return "note-retire " + quantity;
    }

    public static String noteFreeze() {
        return "note-freeze";
    }

    public static String noteUnfreeze() {
        return "note-unfreeze";
    }

    public static String reserveDeposit(String quantity) {
        return "reserve-deposit " + quantity;
    }

    public static String reserveWithdraw(String quantity) {
        return "reserve-withdraw " + quantity;
    }

    public static String noteBankrupt() {
        return "note-bankrupt";
    }

    public static String noteBankruptYes() {
        return "note-bankrupt-yes";
    }

    public static String noteBankruptNo() {
        return "note-bankrupt-no";
    }

    public static String adminFreeze() {
        return "admin-freeze";
    }

    public static String adminUnbacked(String currencyId, String quantity) {
        return "admin-unbacked " + currencyId + " " + quantity;
    }

    public static String adminRetire(String currencyId, String quantity) {
        return "admin-retire " + currencyId + " " + quantity;
    }

    /** Issues the whole remaining reserve room. The core takes no amount. */
    public static String adminBacked(String currencyId) {
        return "admin-backed " + currencyId;
    }

    /** Issues this exact amount against reserves. */
    public static String adminBackedAmount(String currencyId, String quantity) {
        return "admin-backed " + currencyId + " " + quantity;
    }

    public static String adminReserveFromDeposits(String currencyId, String quantity) {
        return "admin-reserve-from-deposits " + currencyId + " " + quantity;
    }

    public static String adminReserveToDeposits(String currencyId, String quantity) {
        return "admin-reserve-to-deposits " + currencyId + " " + quantity;
    }

    /**
     * Switches the issuer's regime. {@code note} is a legal anchor only for peg.
     * Float and hard omit a bank-note anchor.
     */
    /** Every regime may name diamond, copper, or note. A missing anchor omits that word. */
    public static String noteSwitch(String regime, String anchor) {
        String kind = regimeToken(regime);
        String peg = anchorToken(anchor);
        if (peg == null) {
            return "note-switch " + kind;
        }
        return "note-switch " + kind + " " + peg;
    }

    public static String regimeToken(String regime) {
        return switch (regime == null ? "" : regime) {
            case "peg", "PEG", "钉住", "联系汇率制" -> "peg";
            case "hard", "HARD", "定额兑付", "固定汇率制" -> "hard";
            default -> "float";
        };
    }

    public static String anchorToken(String anchor) {
        if (anchor == null || anchor.isBlank()) {
            return null;
        }
        return switch (anchor) {
            case "diamond", "DIAMOND", "钻石" -> "diamond";
            case "copper", "COPPER", "铜锭" -> "copper";
            case "note", "NOTE", "银行券" -> "note";
            default -> null;
        };
    }

    public static String close() {
        return "close";
    }

    /** Told in chat, and on the bank screen, before the name is typed. */
    public static String noteNamePrompt(String regime) {
        String kind = regimeToken(regime);
        String label = switch (kind) {
            case "peg" -> "联系汇率制";
            case "hard" -> "固定汇率制";
            default -> "浮动汇率制";
        };
        return "请在这条命令后面输入个人银行券的名字，然后按回车。你选的是" + label
                + "。名字最多三个汉字或英文字母。";
    }

    /** Chat text, including the leading slash and a trailing space for the name. */
    public static String noteNameCommand(String regime) {
        String kind = switch (regime == null ? "" : regime) {
            case "peg", "PEG", "钉住", "联系汇率制" -> "peg";
            case "hard", "HARD", "定额兑付", "固定汇率制" -> "hard";
            default -> "float";
        };
        return "/bank note create " + kind + " ";
    }
}
