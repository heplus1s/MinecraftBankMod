package dev.jdguo.bank.net;

import java.util.ArrayList;
import java.util.List;

/** Optional bounded transport for snapshots that exceed the legacy string packet. */
public final class UiSnapshotChunks {
    public static final int CHUNK_CHARS = 16384;
    public static final int MAX_CHUNKS = 64;
    public static final int MAX_CHARS = CHUNK_CHARS * MAX_CHUNKS - MAX_CHUNKS;
    public static final long TIMEOUT_MILLIS = 10_000;
    private UiSnapshotChunks() {}

    public static List<String> split(String json) {
        if (json == null || json.isEmpty() || json.length() > MAX_CHARS)
            throw new IllegalArgumentException("snapshot size");
        List<String> parts = new ArrayList<>();
        for (int start = 0; start < json.length();) {
            int end = Math.min(start + CHUNK_CHARS, json.length());
            if (end < json.length() && Character.isHighSurrogate(json.charAt(end - 1))
                    && Character.isLowSurrogate(json.charAt(end))) end--;
            parts.add(json.substring(start, end));
            start = end;
        }
        if (parts.size() > MAX_CHUNKS) throw new IllegalArgumentException("chunk count");
        return List.copyOf(parts);
    }

    /** Main-thread-only assembler. New transfers supersede old ones; no partial UI is shown. */
    public static final class Assembler {
        private StringBuilder text;
        private int transfer, count, next;
        private long started;
        public void clear() { text = null; count = next = 0; }
        public boolean expire(long now) {
            if (text != null && (now < started || now - started > TIMEOUT_MILLIS)) {
                clear();
                return true;
            }
            return false;
        }
        public String accept(int id, int index, int total, String part, long now) {
            if (total < 1 || total > MAX_CHUNKS || index < 0 || index >= total
                    || part == null || part.isEmpty() || part.length() > CHUNK_CHARS) {
                clear();
                throw new IllegalArgumentException("invalid snapshot chunk");
            }
            boolean expired = expire(now);
            if (index == 0) {
                if (text != null && transfer == id) { clear(); throw new IllegalArgumentException("duplicate snapshot start"); }
                text = new StringBuilder(); transfer = id; count = total; next = 0; started = now;
            } else if (expired || text == null || id != transfer || total != count || index != next) {
                clear();
                throw new IllegalArgumentException("incomplete snapshot transfer");
            }
            if (index != next || text.length() + part.length() > MAX_CHARS) {
                clear();
                throw new IllegalArgumentException("snapshot transfer size/order");
            }
            text.append(part); next++;
            if (next != count) return null;
            String json = text.toString(); clear(); return json;
        }
    }
}
