package dev.jdguo.bank.net;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SystemFundingConfirmationTest {
    @Test void explicitConfirmationCannotConfirmAnotherPreviewOrFallBackToLegacy() {
        JsonObject draft = new JsonObject();
        draft.addProperty("direction", "recall");
        draft.addProperty("token", "expected");
        assertFalse(SystemFundingUi.matches(draft, new String[]{"admin-funding-confirm"}));
        assertFalse(SystemFundingUi.matches(draft, new String[]{"admin-funding-confirm", "previous"}));
        assertFalse(SystemFundingUi.matches(draft, new String[]{"admin-funding-confirm", "expected", "extra"}));
        assertTrue(SystemFundingUi.matches(draft, new String[]{"admin-funding-confirm", "expected"}));
        draft.addProperty("direction", "fund");
        assertFalse(SystemFundingUi.matches(draft, new String[]{"admin-funding-confirm"}));
    }
    @Test void oldConfirmationCanOnlyConfirmLegacyInbound() {
        JsonObject draft = new JsonObject();
        draft.addProperty("direction", "fund");
        assertTrue(SystemFundingUi.matches(draft, new String[]{"admin-funding-confirm"}));
        assertFalse(SystemFundingUi.matches(draft, new String[]{"admin-funding-confirm", "token"}));
        draft.addProperty("direction", "recall");
        assertFalse(SystemFundingUi.matches(draft, new String[]{"admin-funding-confirm"}));
    }
}
