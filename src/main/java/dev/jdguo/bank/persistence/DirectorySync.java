package dev.jdguo.bank.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Best-effort directory durability after the ledger's atomic replacement has succeeded. */
final class DirectorySync {
    private final boolean windows;
    private final AtomicBoolean warned = new AtomicBoolean();

    DirectorySync() {
        this(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
    }

    DirectorySync(boolean windows) {
        this.windows = windows;
    }

    void force(Path directory, Consumer<String> warning) throws IOException {
        force(directory, path -> {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
            return new Handle() {
                public void force() throws IOException { channel.force(true); }
                public void close() throws IOException { channel.close(); }
            };
        }, warning);
    }

    void force(Path directory, Opener opener, Consumer<String> warning) throws IOException {
        Handle handle;
        try {
            handle = opener.open(directory);
        } catch (AccessDeniedException denied) {
            // The Windows default provider cannot open a directory as a FileChannel.
            // Do not suppress denial on a custom provider, or during force/close below.
            if (!windows || directory.getFileSystem() != FileSystems.getDefault()) {
                throw denied;
            }
            if (warned.compareAndSet(false, true)) {
                warning.accept("Windows 不支持此方式的目录同步；账本文件已强制写入并原子提交。"
                        + "断电耐久性取决于操作系统，此提示仅显示一次。");
            }
            return;
        }
        try (handle) {
            handle.force();
        }
    }

    @FunctionalInterface
    interface Opener { Handle open(Path directory) throws IOException; }

    interface Handle extends AutoCloseable {
        void force() throws IOException;
        @Override void close() throws IOException;
    }
}
