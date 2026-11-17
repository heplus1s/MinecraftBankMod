package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PersonalQuoteRegressionTest {
    @Test void absentSelectionDoesNotDereferenceNull() {
        assertEquals("请选择个人币", BankRates.selectedPersonalQuote(new JsonObject(), null));
        assertEquals("请选择个人币", BankRates.selectedPersonalQuote(null, null));
        assertEquals("请选择个人币", BankRates.selectedPersonalQuote(snapshot("{\"notes\":[]}"), null));
    }

    @Test void unquotedNoteUsesMatchingStatusOrUnavailable() {
        var note = new BankSnapshot.NoteRow("id-a", "甲币", "12", null);
        assertEquals("暂无牌价", BankRates.selectedPersonalQuote(new JsonObject(), note));
        assertEquals("暂无牌价", BankRates.selectedPersonalQuote(null, note));
        assertEquals("兑换已暂停", BankRates.selectedPersonalQuote(snapshot("""
                {"notes":[null,7,{"id":"id-b","quoteStatus":"其他币"},
                          {"id":"id-a","quoteStatus":"兑换已暂停"}]}
                """), note));
        assertEquals("暂无牌价", BankRates.selectedPersonalQuote(snapshot("{\"notes\":[{\"id\":\"id-a\",\"quoteStatus\":\"\"}]}"), note));
    }

    @Test void missingIdentityCannotBorrowAnotherNotesStatus() {
        var note = new BankSnapshot.NoteRow(null, "旧币", "12", null);
        assertEquals("暂无牌价", BankRates.selectedPersonalQuote(snapshot("{\"notes\":[{\"quoteStatus\":\"错误状态\"}]}"), note));
    }

    @Test void pricedAndZeroValuedNotesRetainActualQuoteAndConfiguredName() {
        var data = snapshot("{\"centralCurrencyName\":\"服务器券\"}");
        assertEquals("现价：2.500000 服务器券", BankRates.selectedPersonalQuote(data,
                new BankSnapshot.NoteRow("id-a", "甲币", "12", "2.500000")));
        assertEquals("现价：0 服务器券", BankRates.selectedPersonalQuote(data,
                new BankSnapshot.NoteRow("id-a", "甲币", "12", "0")));
    }

    @Test void selectionDisappearingDuringRefreshIsSafe() {
        var data = snapshot("{\"notes\":[{\"id\":\"id-a\",\"name\":\"甲币\",\"price\":\"2\"}]}");
        assertTrue(BankRates.selectedPersonalQuote(data, BankSnapshot.notes(data).get(0)).startsWith("现价：2"));
        data.add("notes", JsonParser.parseString("[]"));
        assertEquals("请选择个人币", BankRates.selectedPersonalQuote(data, null));
    }

    @Test void screenActuallyUsesNullSafeQuotePath() throws Exception {
        String screen = Files.readString(Path.of("src/client/java/dev/jdguo/bank/ui/BankTerminalScreen.java"));
        int start = screen.indexOf("private void drawBalanceUnder(");
        String body = screen.substring(start, screen.indexOf("private String centralName()", start));
        assertTrue(body.contains("BankRates.selectedPersonalQuote(data, note)"));
        assertFalse(body.contains("note.id()"));
    }

    private static JsonObject snapshot(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
}
