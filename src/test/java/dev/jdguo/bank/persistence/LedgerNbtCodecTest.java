package dev.jdguo.bank.persistence;

import java.io.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LedgerNbtCodecTest {
    @Test void largeChineseEmojiJsonRoundTripsThroughActualCompressedNbt() throws Exception {
        String json = "{\"memo\":\"" + "中文存款😀\u0000".repeat(30000) + "\"}";
        NbtCompound nbt = new NbtCompound(); nbt.putString("json", "old");
        LedgerNbtCodec.write(nbt, json);
        assertFalse(nbt.contains("json"));
        NbtList chunks = nbt.getList("jsonChunks", NbtElement.STRING_TYPE);
        assertTrue(chunks.size() > 1);
        for (int i=0;i<chunks.size();i++) {
            String chunk=chunks.getString(i);
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length()-1)));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            new DataOutputStream(bytes).writeUTF(chunk); // actual modified UTF serialization bound
            assertTrue(bytes.size()<=48002);
        }
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        NbtIo.writeCompressed(nbt,bytes);
        NbtCompound loaded=NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(json,LedgerNbtCodec.read(loaded));
    }
    @Test void legacyRemainsReadableAndRewritesWithoutOldString() {
        NbtCompound nbt=new NbtCompound();nbt.putString("json","{\"旧\":1}");
        assertEquals("{\"旧\":1}",LedgerNbtCodec.read(nbt));
        LedgerNbtCodec.write(nbt,LedgerNbtCodec.read(nbt));assertFalse(nbt.contains("json"));
        assertEquals("{\"旧\":1}",LedgerNbtCodec.read(nbt));
    }
    @Test void corruptRecognizedChunkDataCannotFallBackToLegacyOrEmpty() {
        NbtCompound nbt=new NbtCompound();LedgerNbtCodec.write(nbt,"{\"a\":1}");
        nbt.putString("json","{\"stale\":1}");nbt.putInt("jsonLength",1);
        assertThrows(IllegalStateException.class,()->LedgerNbtCodec.read(nbt));
        nbt.put("jsonChunks",new NbtList());assertThrows(IllegalStateException.class,()->LedgerNbtCodec.read(nbt));
        assertThrows(IllegalStateException.class,()->LedgerNbtCodec.read(new NbtCompound()));
        NbtCompound wrong=new NbtCompound();wrong.putInt("json",1);
        assertThrows(IllegalStateException.class,()->LedgerNbtCodec.read(wrong));
    }
}
