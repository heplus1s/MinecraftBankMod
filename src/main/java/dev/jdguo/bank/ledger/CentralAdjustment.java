package dev.jdguo.bank.ledger;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Server-owned, immutable preview. No preview mutates the live books. */
public record CentralAdjustment(boolean allowed, String reason, String mode, String direction,
        String currency, BigInteger amount, BigDecimal target, BigDecimal rateBefore, BigDecimal rateAfter,
        BigDecimal reserveBefore, BigDecimal reserveAfter, BigDecimal treasuryBefore, BigDecimal treasuryAfter,
        BigDecimal liabilitiesBefore, BigDecimal liabilitiesAfter, String fingerprint) {
    private static final UUID OPERATOR = new UUID(0, 0);
    public static CentralAdjustment preview(BankBooks books, String mode, String option, String input) {
        if (input == null || input.length() > 35) return denied("无效数值");
        // UI uses ordinary decimal notation. Reject exponents before BigDecimal parsing:
        // significant-digit precision alone does not bound exponent-driven allocations.
        if ("quantity".equals(mode) && !input.matches("[0-9]{1,31}")) return denied("请输入正整数数量");
        if ("reserve".equals(mode) && !input.matches("[0-9]{1,26}(\\.[0-9]{1,4})?"))
            return denied("目标汇率需使用普通数值，整数最多 26 位、小数最多 4 位");
        BankBooks sim = books.copyExact();
        String note = sim.primaryVirtual() == null ? null : sim.primaryVirtual().id();
        if (note == null) return denied("未配置央行券");
        BigDecimal beforeRate = sim.unitsPer("diamond", note);
        BigDecimal liability = sim.centralIssuedExact();
        BigInteger amount; String currency, direction; BigDecimal target = null;
        try {
            if ("quantity".equals(mode)) {
                amount = new BigInteger(input);
                if (amount.signum() <= 0 || amount.bitLength() > 100 || !("increase".equals(option) || "decrease".equals(option)))
                    return denied("请输入有效正整数数量");
                currency = note; direction = option;
            } else if ("reserve".equals(mode)) {
                currency = option;
                if (!"diamond".equals(currency) && !"copper".equals(currency)) return denied("请选择钻石或铜锭准备金");
                target = new BigDecimal(input);
                if (target.signum() <= 0 || target.scale() > Money.SCALE || target.precision() > 30 || liability.signum() <= 0)
                    return denied("目标必须为正数、最多四位小数，且已有央行券发行量");
                BigInteger signed = solve(sim, currency, target, note);
                if (signed == null) return denied("当前资金或整数实物精度无法可靠接近目标；不会动用玩家保管款");
                if (signed.signum() == 0) return denied("当前汇率已是该目标下最接近的整数实物结果，无需划转");
                direction = signed.signum() > 0 ? "increase" : "decrease";
                amount = signed.abs();
                // Solver leaves its private simulation at an arbitrary candidate. Reset before final quote.
                sim = books.copyExact();
            } else return denied("未知管理模式");
        } catch (RuntimeException invalid) { return denied("请输入有效的数量或目标汇率"); }
        BigDecimal reserveBefore = sim.supply(currency).reserveExact();
        BigDecimal treasuryBefore = sim.treasuryExact(currency);
        TxResult result = apply(sim, mode, direction, currency, amount);
        if (!result.success()) return denied(result.message());
        BigDecimal rate = sim.unitsPer("diamond", note);
        return new CentralAdjustment(true, "reserve".equals(mode) ? "整件实物划转，目标误差上限为 1%（最低一个报价精度）；实际汇率以预览为准，发行量不变" :
                "increase".equals(direction) ? "增发央行券进入央行自有储蓄，可用于系统拨款；不增加准备金或玩家余额" : "仅从央行自有储蓄回收央行券，不扣玩家或系统账户余额", mode, direction, currency, amount, target,
                beforeRate, rate, reserveBefore, sim.supply(currency).reserveExact(), treasuryBefore, sim.treasuryExact(currency),
                liability, sim.centralIssuedExact(), fingerprint(books));
    }

    /** Searches real quote results, including copper/diamond quote feedback; never assumes fixed metal prices. */
    private static BigInteger solve(BankBooks sim, String currency, BigDecimal target, String note) {
        BigInteger low = sim.supply(currency).freeReserve().negate(), high = Money.units(sim.treasuryExact(currency));
        if (low.bitLength() > 100 || high.bitLength() > 100) return null;
        Evaluator e = new Evaluator(sim, currency, note);
        BigInteger previousX = null; BigDecimal previous = null;
        BigInteger best = null; BigDecimal bestError = null;
        // Include zero to avoid losing a bracket around a narrow feasible region.
        java.util.TreeSet<BigInteger> samples = new java.util.TreeSet<>(); samples.add(BigInteger.ZERO);
        for (int i=0;i<=64;i++) samples.add(low.add(high.subtract(low).multiply(BigInteger.valueOf(i)).divide(BigInteger.valueOf(64))));
        for (BigInteger x : samples) {
            BigDecimal rate = e.at(x);
            if (rate == null) { previousX = null; previous = null; continue; }
            if (rate.compareTo(target) == 0) return x;
            if (previous != null && previous.compareTo(target) != rate.compareTo(target)) {
                BigInteger a = previousX, b = x; BigDecimal ar = previous, br = rate;
                while (b.subtract(a).compareTo(BigInteger.ONE) > 0) {
                    BigInteger mid = a.add(b).shiftRight(1); BigDecimal mr = e.at(mid);
                    if (mr == null) { a = null; break; } // Never bridge an invalid quote region.
                    if (mr.compareTo(target) == 0) return mid;
                    if (mr.compareTo(target) == ar.compareTo(target)) { a = mid; ar = mr; } else { b = mid; br = mr; }
                }
                if (a != null) {
                    BigDecimal ae = ar.subtract(target).abs(), be = br.subtract(target).abs();
                    BigInteger candidate = ae.compareTo(be) <= 0 ? a : b;
                    BigDecimal error = ae.min(be);
                    if (bestError == null || error.compareTo(bestError) < 0) { best=candidate; bestError=error; }
                }
            }
            previousX = x; previous = rate;
        }
        // Quote discontinuities must not be mistaken for a reachable target. A whole-item
        // result may differ slightly; require it to be within 1% (or one quote tick).
        BigDecimal tolerance = target.multiply(new BigDecimal("0.01")).max(Money.UNIT);
        return bestError != null && bestError.compareTo(tolerance) <= 0 ? best : null;
    }
    private static final class Evaluator {
        final BankBooks sim; final String currency, note; BigInteger at = BigInteger.ZERO;
        Evaluator(BankBooks sim, String currency, String note) { this.sim=sim; this.currency=currency; this.note=note; }
        BigDecimal at(BigInteger position) {
            BigInteger delta = position.subtract(at);
            if (delta.signum() != 0) {
                TxResult r = sim.transferReserve(currency, new BigDecimal(delta.abs()), delta.signum()>0);
                if (!r.success()) return null;
                at = position;
            }
            return sim.unitsPer("diamond", note);
        }
    }
    private static TxResult apply(BankBooks b, String mode, String direction, String currency, BigInteger amount) {
        if ("reserve".equals(mode)) return b.transferReserve(currency, new BigDecimal(amount), "increase".equals(direction));
        return "increase".equals(direction) ? b.creditUnbacked(OPERATOR,currency,amount) : b.retire(OPERATOR,currency,amount);
    }
    public TxResult confirm(BankBooks b) {
        if (!allowed || !fingerprint.equals(fingerprint(b))) return TxResult.fail("stale_preview","账本已变化，请重新预览");
        CentralAdjustment fresh = preview(b, mode, "reserve".equals(mode)?currency:direction,
                "reserve".equals(mode)?target.toPlainString():amount.toString());
        if (!fresh.allowed || !amount.equals(fresh.amount) || !direction.equals(fresh.direction))
            return TxResult.fail("stale_preview","操作结果已变化，请重新预览");
        return apply(b,mode,direction,currency,amount);
    }
    private static String fingerprint(BankBooks b) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b.toJson().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static CentralAdjustment denied(String reason) { return new CentralAdjustment(false,reason,"","","",BigInteger.ZERO,null,null,null,null,null,null,null,null,null,""); }
    public JsonObject json() {
        JsonObject j = new JsonObject(); j.addProperty("allowed",allowed); j.addProperty("reason",reason);
        j.addProperty("mode",mode);j.addProperty("direction",direction);j.addProperty("currency",currency);j.addProperty("amount",amount.toString());
        add(j,"target",target);add(j,"rateBefore",rateBefore);add(j,"rateAfter",rateAfter);add(j,"reserveBefore",reserveBefore);add(j,"reserveAfter",reserveAfter);
        add(j,"treasuryBefore",treasuryBefore);add(j,"treasuryAfter",treasuryAfter);add(j,"liabilitiesBefore",liabilitiesBefore);add(j,"liabilitiesAfter",liabilitiesAfter);return j;
    }
    private static void add(JsonObject j,String key,BigDecimal value) { if(value != null) j.addProperty(key,Money.stored(value)); }
}
