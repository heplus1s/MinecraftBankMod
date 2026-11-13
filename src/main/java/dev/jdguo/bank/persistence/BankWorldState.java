package dev.jdguo.bank.persistence;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** NBT holds the same JSON document that is also written beside the world for backups. */
public final class BankWorldState extends PersistentState {
    public static final String ID = "bankmod";

    private final BankBooks books;

    public BankWorldState(BankBooks books) {
        this.books = books;
    }

    public BankBooks books() {
        return books;
    }

    public static BankWorldState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(
                BankWorldState::fromNbt,
                () -> new BankWorldState(BankBooks.create(BankRuntime.config)),
                ID);
    }

    public static BankWorldState fromNbt(NbtCompound nbt) {
        BankConfig config = BankRuntime.config == null ? BankConfig.defaults() : BankRuntime.config;
        String json = nbt.contains("json") ? nbt.getString("json") : "";
        if (!json.isBlank()) {
            try {
                return new BankWorldState(BankBooks.fromJson(json, config));
            } catch (RuntimeException error) {
                BankRuntime.LOGGER.error("银行存档损坏，尝试读取旁路 JSON", error);
            }
        }
        Path backup = backupPath(BankRuntime.server);
        if (backup != null && Files.exists(backup)) {
            try {
                return new BankWorldState(BankBooks.fromJson(Files.readString(backup), config));
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException("银行存档和旁路 JSON 都无法读取: " + backup, error);
            }
        }
        if (!json.isBlank()) {
            throw new IllegalStateException("银行存档无法读取，且没有旁路 JSON");
        }
        return new BankWorldState(BankBooks.create(config));
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putString("json", books.toJson());
        nbt.putInt("version", BankBooks.SNAPSHOT_VERSION);
        writeBackup();
        return nbt;
    }

    public void writeBackup() {
        Path path = backupPath(BankRuntime.server);
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, books.toJson());
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            BankRuntime.LOGGER.warn("写不了银行旁路存档", error);
        }
    }

    public static Path backupPath(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        return server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve("bankmod-ledger.json");
    }

    public static String overworldId() {
        return World.OVERWORLD.getValue().toString();
    }
}
