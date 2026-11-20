package dev.jdguo.bank.market;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

/** Inventory checkpoint, not a cross-file transaction. The caller must durably lock its journal first. */
public final class DurablePlayerInventory {
    private DurablePlayerInventory() {}
    /** A receipt in playerdata alone is not proof for the integrated-server host. */
    public static boolean integratedReceiptConfirmed(ServerPlayerEntity player, String marker) throws IOException {
        var server = player.getServer();
        if (server == null) throw new IOException("server unavailable");
        if (server.isDedicated()) return true;
        Path levelFile = server.getSavePath(WorldSavePath.ROOT).resolve("level.dat");
        NbtCompound root = NbtIo.readCompressed(levelFile.toFile());
        if (root == null) throw new IOException("integrated level checkpoint unavailable");
        NbtCompound host = root.getCompound("Data").getCompound("Player");
        return hostReceiptConfirmed(player.getUuid(), host, marker);
    }

    public static boolean integratedHostReceiptPresent(ServerPlayerEntity player, String marker) throws IOException {
        var server = player.getServer();
        if (server == null) throw new IOException("server unavailable");
        if (server.isDedicated()) return false;
        NbtCompound root = NbtIo.readCompressed(server.getSavePath(WorldSavePath.ROOT).resolve("level.dat").toFile());
        if (root == null) throw new IOException("integrated level checkpoint unavailable");
        NbtCompound host = root.getCompound("Data").getCompound("Player");
        if (!host.containsUuid("UUID")) throw new IOException("integrated host identity unavailable");
        return host.getUuid("UUID").equals(player.getUuid()) && hostReceiptConfirmed(player.getUuid(), host, marker);
    }

    static boolean hostReceiptConfirmed(java.util.UUID player, NbtCompound host, String marker) throws IOException {
        if (!host.containsUuid("UUID")) throw new IOException("integrated host identity unavailable");
        if (!host.getUuid("UUID").equals(player)) return true;
        var tags = host.getList("Tags", 8);
        for (int i = 0; i < tags.size(); i++) if (marker.equals(tags.getString(i))) return true;
        return false;
    }

    static void verifyHostCheckpoint(NbtCompound expected, NbtCompound host) throws IOException {
        if (!expected.getList("Inventory", 10).equals(host.getList("Inventory", 10)))
            throw new IOException("integrated host inventory checkpoint mismatch");
        if (!expected.getList("Tags", 8).equals(host.getList("Tags", 8)))
            throw new IOException("integrated host receipt checkpoint mismatch");
    }

    public static void save(ServerPlayerEntity player) {
        var server = player.getServer();
        if (server == null || !server.isOnThread()) throw new IllegalStateException("server thread required");
        Path file = server.getSavePath(WorldSavePath.ROOT).resolve("playerdata").resolve(player.getUuid() + ".dat");
        Path temporary = file.resolveSibling(player.getUuid() + ".arcade.tmp");
        // The integrated server also keeps its host in level.dat. Let vanilla update that
        // copy before verifying it; writing playerdata alone is insufficient for singleplayer.
        if (!server.isDedicated()) server.saveAll(false, true, false);
        NbtCompound expected = player.writeNbt(new NbtCompound());
        expected.putInt("DataVersion", SharedConstants.getGameVersion().getSaveVersion().getId());
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(expected, temporary.toFile());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            NbtCompound read = NbtIo.readCompressed(file.toFile());
            if (read == null || !expected.equals(read)) throw new IOException("player checkpoint read-back mismatch");
            if (!server.isDedicated()) {
                Path levelFile = server.getSavePath(WorldSavePath.ROOT).resolve("level.dat");
                NbtCompound root = NbtIo.readCompressed(levelFile.toFile());
                if (root == null) throw new IOException("integrated level checkpoint unavailable");
                NbtCompound host = root.getCompound("Data").getCompound("Player");
                if (!host.containsUuid("UUID")) throw new IOException("integrated host identity unavailable");
                if (host.getUuid("UUID").equals(player.getUuid())) {
                    verifyHostCheckpoint(expected, host);
                    try (FileChannel channel = FileChannel.open(levelFile, StandardOpenOption.WRITE)) { channel.force(true); }
                }
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Player inventory checkpoint not confirmed; keep Arcade delivery locked", error);
        }
    }
}
