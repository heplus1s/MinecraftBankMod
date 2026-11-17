package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankAdjustmentViewTest {
    @Test void emptyModeRejectionsRemainVisible() {
        for (String reason : new String[]{"请输入正整数数量", "央行自有储蓄不足", "目标汇率不可达"}) {
            JsonObject preview = new JsonObject();
            preview.addProperty("mode", "");
            preview.addProperty("allowed", false);
            preview.addProperty("reason", reason);
            assertEquals("无法执行：" + reason, BankAdjustmentView.rejection(preview));
        }
    }
    @Test void acceptedAndMissingPreviewsHaveNoRejection() {
        assertNull(BankAdjustmentView.rejection(null));
        JsonObject preview = new JsonObject(); preview.addProperty("allowed", true);
        assertNull(BankAdjustmentView.rejection(preview));
    }
    @Test void malformedPreviewFailsClosedWithExplanation() {
        assertEquals("无法执行：预览无效，请重新计算", BankAdjustmentView.rejection(new JsonObject()));
    }
}
