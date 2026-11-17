package dev.jdguo.bank.ui;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BankInputRegressionTest {
    @Test void chineseHelpWrapsWithoutSpaces() {
        String help = "请把要兑付的汇票拿在主手。一次只兑付这一张，不会扫描背包。";
        var lines = BankText.lines(help, 8, String::length);
        assertTrue(lines.size() > 1);
        assertEquals(help, String.join("", lines));
        assertTrue(lines.stream().allMatch(s -> s.length() <= 8));
    }
    @Test void allPaneFieldsReceiveKeyboardAndCreationDoesNotOpenChat() throws Exception {
        String pane = Files.readString(Path.of("src/client/java/dev/jdguo/bank/ui/IssuancePane.java"));
        String screen = Files.readString(Path.of("src/client/java/dev/jdguo/bank/ui/BankTerminalScreen.java"));
        assertTrue(pane.contains("field.keyPressed(keyCode, scanCode, modifiers)"));
        assertTrue(pane.contains("field.charTyped(chr, modifiers)"));
        assertTrue(screen.contains("issuancePane.keyPressed(keyCode, scanCode, modifiers)"));
        assertTrue(screen.contains("send(\"note-create \" + createRegime"));
        assertFalse(screen.contains("case \"regime\" -> askForNoteName"));
    }
}
