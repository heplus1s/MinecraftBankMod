package dev.jdguo.bank.ui;

import java.math.BigDecimal;

final class IssuancePolicyForm {
    private IssuancePolicyForm() {}

    static String bandCommand(String active, String low, String high) {
        if (!"peg".equals(active)) throw new IllegalArgumentException("只有联系汇率制可保存区间。");
        BigDecimal min = bound(low), max = bound(high);
        if (min.compareTo(max) > 0) throw new IllegalArgumentException("下限不能高于上限。");
        return "note-policy-band " + min.stripTrailingZeros().toPlainString() + " " + max.stripTrailingZeros().toPlainString();
    }

    static String quantityPreview(String direction, String quantity) {
        if (!"increase".equals(direction) && !"decrease".equals(direction))
            throw new IllegalArgumentException("请选择增发或收回。");
        return "note-quantity-preview " + direction + " " + whole(quantity).toString();
    }

    static boolean samePositiveWhole(String a, String b) {
        try { return whole(a).equals(whole(b)); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static java.math.BigInteger whole(String text) {
        if (text == null || !text.matches("[0-9]{1,24}"))
            throw new IllegalArgumentException("请输入正整数个人币数量。");
        var value = new java.math.BigInteger(text);
        if (value.signum() <= 0) throw new IllegalArgumentException("数量必须大于 0。");
        return value;
    }

    private static BigDecimal bound(String text) {
        if (text == null || !text.matches("[0-9]{1,18}(\\.[0-9]{1,4})?"))
            throw new IllegalArgumentException("上下限请输入正数（最多 18 位整数、4 位小数）。");
        BigDecimal result = new BigDecimal(text);
        if (result.signum() <= 0) throw new IllegalArgumentException("上下限必须大于 0。");
        return result;
    }
}
