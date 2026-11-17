package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;

/** Rejections may not have a mode; never hide a server-side validation failure. */
public final class BankAdjustmentView {
    private BankAdjustmentView() { }
    public static String rejection(JsonObject preview) {
        if (preview == null) return null;
        if (preview.has("allowed") && preview.get("allowed").getAsBoolean()) return null;
        String reason = BankSnapshot.text(preview, "reason");
        return "无法执行：" + (reason == null || reason.isBlank() ? "预览无效，请重新计算" : reason);
    }
}
