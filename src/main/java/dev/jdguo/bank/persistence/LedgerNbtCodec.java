package dev.jdguo.bank.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

/** Each chunk is at most 48,000 modified-UTF bytes, below DataOutput.writeUTF's 65,535 limit. */
final class LedgerNbtCodec {
    static final int CHUNK_CHARS = 16000;
    private LedgerNbtCodec() {}

    static void write(NbtCompound nbt, String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("Empty bank ledger");
        NbtList chunks = new NbtList();
        for (int start = 0; start < json.length();) {
            int end = Math.min(start + CHUNK_CHARS, json.length());
            if (end < json.length() && Character.isHighSurrogate(json.charAt(end - 1))
                    && Character.isLowSurrogate(json.charAt(end))) end--;
            chunks.add(NbtString.of(json.substring(start, end)));
            start = end;
        }
        nbt.remove("json");
        nbt.putInt("jsonEncoding", 1);
        nbt.putInt("jsonLength", json.length());
        nbt.putString("jsonSha256", digest(json));
        nbt.put("jsonChunks", chunks);
    }

    static String read(NbtCompound nbt) {
        if (nbt.contains("jsonEncoding") || nbt.contains("jsonChunks") || nbt.contains("jsonLength") || nbt.contains("jsonSha256")) {
            if (!nbt.contains("jsonEncoding", NbtElement.INT_TYPE) || nbt.getInt("jsonEncoding") != 1
                    || !nbt.contains("jsonLength", NbtElement.INT_TYPE) || nbt.getInt("jsonLength") <= 0
                    || !nbt.contains("jsonSha256", NbtElement.STRING_TYPE)
                    || !nbt.contains("jsonChunks", NbtElement.LIST_TYPE)) throw new IllegalStateException("Invalid chunked bank ledger header");
            NbtList chunks = nbt.getList("jsonChunks", NbtElement.STRING_TYPE);
            if (chunks.isEmpty()) throw new IllegalStateException("Missing bank ledger chunks");
            StringBuilder json = new StringBuilder();
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.getString(i);
                if (chunk.isEmpty() || chunk.length() > CHUNK_CHARS) throw new IllegalStateException("Invalid bank ledger chunk");
                json.append(chunk);
            }
            String value = json.toString();
            if (value.length() != nbt.getInt("jsonLength") || !digest(value).equals(nbt.getString("jsonSha256")))
                throw new IllegalStateException("Bank ledger chunks failed integrity check");
            return value;
        }
        if (!nbt.contains("json", NbtElement.STRING_TYPE) || nbt.getString("json").isBlank())
            throw new IllegalStateException("Missing or invalid legacy bank ledger");
        return nbt.getString("json");
    }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
