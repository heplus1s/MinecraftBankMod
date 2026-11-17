package dev.jdguo.bank.net;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UiSnapshotChunksTest {
    @Test void largeUnicodeSnapshotRoundTripsWithoutTruncation() {
        String json = "{\"names\":\"" + "中文𠮷".repeat(50000) + "\"}";
        var parts = UiSnapshotChunks.split(json);
        assertTrue(parts.size() > 2);
        var receiver = new UiSnapshotChunks.Assembler();
        String result = null;
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            assertTrue(part.length() <= UiSnapshotChunks.CHUNK_CHARS);
            assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
            result = receiver.accept(123, i, parts.size(), part, i);
            if (i < parts.size() - 1) assertNull(result);
        }
        assertEquals(json, result);
    }
    @Test void maxSizeAllowedAndOversizeRejected() {
        String payload = "a".repeat(UiSnapshotChunks.MAX_CHARS);
        assertTrue(UiSnapshotChunks.split(payload).size() <= UiSnapshotChunks.MAX_CHUNKS);
        assertThrows(IllegalArgumentException.class, () -> UiSnapshotChunks.split(payload + "a"));
        assertThrows(IllegalArgumentException.class, () -> UiSnapshotChunks.split(""));
    }
    @Test void rejectsBadHeadersBeforeAssembly() {
        var r = new UiSnapshotChunks.Assembler();
        assertThrows(IllegalArgumentException.class, () -> r.accept(1, 0, 65, "a", 0));
        assertThrows(IllegalArgumentException.class, () -> r.accept(1, -1, 2, "a", 0));
        assertThrows(IllegalArgumentException.class, () -> r.accept(1, 1, 1, "a", 0));
        assertThrows(IllegalArgumentException.class, () -> r.accept(1, 0, 1, "a".repeat(16385), 0));
    }
    @Test void rejectsMissingOrDuplicateOrReorderedParts() {
        var r = new UiSnapshotChunks.Assembler();
        assertThrows(IllegalArgumentException.class, () -> r.accept(1, 1, 2, "b", 0));
        assertNull(r.accept(1, 0, 3, "a", 0));
        assertThrows(IllegalArgumentException.class, () -> r.accept(1, 2, 3, "c", 0));
        assertNull(r.accept(2, 0, 2, "a", 0));
        assertThrows(IllegalArgumentException.class, () -> r.accept(2, 0, 2, "a", 0));
    }
    @Test void changedTransferOrCountCannotJoinOldPayload() {
        var r = new UiSnapshotChunks.Assembler();
        assertNull(r.accept(1, 0, 2, "a", 0));
        assertThrows(IllegalArgumentException.class, () -> r.accept(2, 1, 2, "b", 1));
        assertNull(r.accept(3, 0, 2, "a", 0));
        assertThrows(IllegalArgumentException.class, () -> r.accept(3, 1, 3, "b", 1));
    }
    @Test void timeoutAndDisconnectClearPartialData() {
        var r = new UiSnapshotChunks.Assembler();
        r.accept(1, 0, 2, "a", 0);
        assertTrue(r.expire(10001));
        assertFalse(r.expire(10002));
        assertThrows(IllegalArgumentException.class, () -> r.accept(1, 1, 2, "b", 10002));
        r.accept(2, 0, 2, "a", 20000);
        r.clear();
        assertThrows(IllegalArgumentException.class, () -> r.accept(2, 1, 2, "b", 20001));
    }
    @Test void newerTransferReplacesOldWithoutPartialDelivery() {
        var r = new UiSnapshotChunks.Assembler();
        assertNull(r.accept(1, 0, 2, "old", 0));
        assertNull(r.accept(2, 0, 2, "new", 1));
        assertEquals("newdata", r.accept(2, 1, 2, "data", 2));
    }
    @Test void aggregateLimitIsEnforced() {
        var r = new UiSnapshotChunks.Assembler();
        String part = "a".repeat(UiSnapshotChunks.CHUNK_CHARS);
        for (int i = 0; i < 63; i++) assertNull(r.accept(1, i, 64, part, i));
        assertThrows(IllegalArgumentException.class, () -> r.accept(1, 63, 64, part, 64));
    }
}
