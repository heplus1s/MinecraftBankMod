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

    private BankBooks books;

    public BankWorldState(BankBooks books) {
        this.books = books;
    }

    public BankBooks books() {
        return books;
    }

    /** Publishes a prepared ledger only after its complete checkpoint is durable. */
    public boolean commit(BankBooks candidate) {
        Path path = backupPath(BankRuntime.server);
        if (path == null) return false;
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".transaction.tmp");
            byte[] bytes = candidate.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (var channel = java.nio.channels.FileChannel.open(tmp,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // Never silently downgrade economic commits to a non-atomic replacement.
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            try (var directory = java.nio.channels.FileChannel.open(path.getParent(), java.nio.file.StandardOpenOption.READ)) {
                directory.force(true);
            } catch (UnsupportedOperationException unsupported) {
                BankRuntime.LOGGER.warn("文件系统不支持目录同步，断电耐久性取决于操作系统", unsupported);
            } catch (java.nio.file.AccessDeniedException denied) {
                BankRuntime.LOGGER.warn("目录同步被拒绝，文件已提交；断电耐久性取决于操作系统", denied);
            } catch (IOException directoryError) {
                // The atomic rename already succeeded. Do not report a false rollback or
                // overwrite it with the old in-memory book on a later ordinary save.
                BankRuntime.LOGGER.warn("目录同步失败，文件已提交；请检查存储设备", directoryError);
            }
            books = candidate;
            markDirty();
            return true;
        } catch (IOException error) {
            BankRuntime.LOGGER.error("银行事务未提交，保留原账本", error);
            return false;
        }
    }

    public static BankWorldState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(
                BankWorldState::fromNbt,
                () -> fromNbt(new NbtCompound()),
                ID);
    }

    public static BankWorldState fromNbt(NbtCompound nbt) {
        BankConfig config = BankRuntime.config == null ? BankConfig.defaults() : BankRuntime.config;
        String json = nbt.contains("json") ? nbt.getString("json") : "";
        BankBooks main = null;
        if (!json.isBlank()) {
            try {
                main = BankBooks.fromJson(json, config);
            } catch (RuntimeException error) {
                BankRuntime.LOGGER.error("银行存档损坏，尝试读取旁路 JSON", error);
            }
        }
        Path backup = backupPath(BankRuntime.server);
        if (backup != null && Files.exists(backup)) {
            try {
                BankBooks recovered = BankBooks.fromJson(Files.readString(backup), config);
                if (main == null || recovered.checkpoint() > main.checkpoint()) {
                    main = recovered;
                }
            } catch (IOException | RuntimeException error) {
                if (main == null) {
                    throw new IllegalStateException("银行存档和旁路 JSON 都无法读取: " + backup, error);
                }
                BankRuntime.LOGGER.warn("银行旁路存档无法读取，使用世界存档", error);
            }
        }
        if (main != null) {
            return new BankWorldState(main);
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

    public boolean writeBackup() {
        // Routine checkpoints must not overwrite confirmed transactions through a weaker path.
        return commit(books);
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
