package dev.jdguo.bank.ledger;

import com.google.gson.JsonObject;
import dev.jdguo.bank.market.NoteRegime;
import dev.jdguo.bank.market.NoteTrade;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.UUID;

/** The issuer's amount is previewed on a detached ledger and posted only when that preview is still exact. */
public record PersonalQuantityPlan(UUID issuer, String direction, BigInteger amount, State before,
                                   State after, boolean allowed, String reason) {
    public record State(UUID noteId, NoteRegime regime, String anchor, BigDecimal holding, BigDecimal available,
                        BigInteger issued, BigDecimal active, BigDecimal reserve, BigInteger locked,
                        boolean redemptionPaused, boolean pending, BankBooks.PersonalPolicy policy) {}

    public static PersonalQuantityPlan preview(BankBooks books, UUID issuer, String direction, String input) {
        if (!("increase".equals(direction) || "decrease".equals(direction))
                || input == null || !input.matches("[0-9]{1,32}"))
            return rejected(issuer, direction, BigInteger.ZERO, null, "请输入正整数数量（最多 32 位）。");
        BigInteger amount = new BigInteger(input);
        if (amount.signum() <= 0)
            return rejected(issuer, direction, amount, null, "请输入正整数数量。");
        return simulate(books, issuer, direction, amount);
    }

    private static PersonalQuantityPlan simulate(BankBooks books, UUID issuer, String direction, BigInteger amount) {
        BankBooks detached = books.copyExact();
        State before = state(detached, issuer);
        if (before == null) return rejected(issuer, direction, amount, null, "只有发行人可以调整本人个人币。");
        if (before.pending()) return rejected(issuer, direction, amount, before, "还有待完成结算，当前不能增发或回收。");
        NoteTrade transaction = execute(detached, issuer, direction, amount, before.regime());
        if (!transaction.success()) return rejected(issuer, direction, amount, before, rejection(transaction.code()));
        return new PersonalQuantityPlan(issuer, direction, amount, before, state(detached, issuer), true,
                "请核对数量和余额变化，确认前不会改动账本。");
    }

    private static PersonalQuantityPlan rejected(UUID issuer, String direction, BigInteger amount, State before, String reason) {
        return new PersonalQuantityPlan(issuer, direction, amount, before, null, false, reason);
    }

    private static State state(BankBooks books, UUID issuer) {
        var note = books.personalNote(issuer);
        if (note == null) return null;
        BigDecimal holding = books.personalHoldingExact(note.id(), issuer);
        BigDecimal available = holding.subtract(new BigDecimal(books.personalLockedOffer(note.id(), issuer))).max(BigDecimal.ZERO);
        return new State(note.id(), note.regime(), note.anchor(), holding, available, note.circulation(),
                books.personalActiveExact(note), books.effectiveBackingExact(note), note.locked(), note.redemptionPaused(),
                books.noteHasPending(note.id()), books.personalPolicy(note.id()));
    }

    private static NoteTrade execute(BankBooks books, UUID issuer, String direction, BigInteger amount, NoteRegime regime) {
        if ("decrease".equals(direction)) return books.retirePersonalNote(issuer, amount);
        return regime == NoteRegime.FLOAT ? books.issuePersonalNoteUnbacked(issuer, amount) : books.issuePersonalNote(issuer, amount);
    }

    public TxResult confirm(BankBooks books, UUID actor) {
        if (actor == null || !actor.equals(issuer) || !allowed)
            return TxResult.fail("invalid_plan", "未执行：该预览不能确认。");
        if (!before.equals(state(books, actor)))
            return TxResult.fail("stale_plan", "未执行：个人币制度、持有量或准备金有变化，请重新预览。");
        PersonalQuantityPlan check = simulate(books, issuer, direction, amount);
        if (!check.allowed || !after.equals(check.after))
            return TxResult.fail("stale_plan", "未执行：调整条件或结果已变化，请重新预览。");
        NoteTrade transaction = execute(books, issuer, direction, amount, before.regime());
        return transaction.success() ? TxResult.ok(amount, "decrease".equals(direction) ? "已按预览回收个人币。" : "已按预览增发个人币。")
                : TxResult.fail(transaction.code(), rejection(transaction.code()));
    }

    private static String rejection(String code) {
        return switch (code) {
            case "reserve" -> "准备金不足，当前制度要求足额支持本次增发。";
            case "central_reserve" -> "央行券准备金未达到当前制度的要求。";
            case "funds" -> "发行人自己的可用个人币不足；挂牌和他人持有的币不能回收。";
            case "bankrupt" -> "货币已关闭或正在更换准备金，当前不能调整数量。";
            default -> "本次调整不符合当前个人币的发行要求。";
        };
    }

    public JsonObject json() {
        JsonObject result = new JsonObject();
        result.addProperty("allowed", allowed);
        result.addProperty("reason", reason);
        result.addProperty("direction", direction);
        result.addProperty("amount", amount.toString());
        if (before != null) {
            result.addProperty("noteId", before.noteId().toString());
            result.addProperty("regime", before.regime().name().toLowerCase(Locale.ROOT));
            result.addProperty("anchor", before.anchor());
            writeState(result, before, "Before");
        }
        if (after != null) writeState(result, after, "After");
        return result;
    }

    private static void writeState(JsonObject json, State state, String suffix) {
        json.addProperty("holding" + suffix, Money.stored(state.holding()));
        json.addProperty("available" + suffix, Money.stored(state.available()));
        json.addProperty("issued" + suffix, state.issued().toString());
        json.addProperty("active" + suffix, Money.stored(state.active()));
        json.addProperty("reserve" + suffix, Money.stored(state.reserve()));
        json.addProperty("locked" + suffix, state.locked().toString());
    }
}
