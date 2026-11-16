package dev.jdguo.bank.ui;

/**
 * Account exchange. Hard currencies use {@code exchange}.
 * A personal note uses buy, sell, or swap, which do not touch the issuer's reserves.
 * A personal note against copper or diamonds is not this action; that is 通兑.
 */
public final class BankExchange {
    public static final String PERSONAL = "pn:";
    public static final String REFUSAL = "个人银行券在这里只能和银行券，或另一张个人银行券兑换。铜锭、钻石兑付发行人的准备金，请用个人银行券交易里的通兑。";

    private BankExchange() {
    }

    public static String idForNote(String name) {
        return PERSONAL + name;
    }

    public static boolean personal(String id) {
        return id != null && id.startsWith(PERSONAL);
    }

    public static String noteName(String id) {
        return personal(id) ? id.substring(PERSONAL.length()) : id;
    }

    public static boolean centralNote(String id) {
        return "note".equals(id);
    }

    /** @return the existing payload, or null when this pair is not an account exchange */
    public static String command(String payId, String receiveId, String quantity) {
        if (payId == null || receiveId == null || payId.isBlank() || receiveId.isBlank() || payId.equals(receiveId)) {
            return null;
        }
        boolean payNote = personal(payId);
        boolean receiveNote = personal(receiveId);
        if (!payNote && !receiveNote) {
            return BankActions.exchange(payId, receiveId, quantity);
        }
        if (payNote && receiveNote) {
            return BankActions.noteSwap(noteName(payId), noteName(receiveId), quantity);
        }
        if (payNote && centralNote(receiveId)) {
            return BankActions.noteSell(noteName(payId), quantity);
        }
        if (receiveNote && centralNote(payId)) {
            return BankActions.noteBuy(noteName(receiveId), quantity);
        }
        return null;
    }
}
