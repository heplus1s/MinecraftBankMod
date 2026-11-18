package dev.jdguo.bank.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DirectorySyncTest {
    @TempDir Path temp;

    @Test void repeatedWindowsOpenDenialWarnsOnceButKeepsChecking() throws Exception {
        DirectorySync sync = new DirectorySync(true);
        var warnings = new ArrayList<String>();
        AtomicInteger attempts = new AtomicInteger();
        DirectorySync.Opener denied = path -> {
            attempts.incrementAndGet();
            throw new AccessDeniedException(path.toString());
        };
        for (int i = 0; i < 5; i++) sync.force(temp, denied, warnings::add);
        assertEquals(1, warnings.size());
        assertEquals(5, attempts.get());
    }

    @Test void nonWindowsOpenDenialIsNotSuppressed() {
        var error = new AccessDeniedException(temp.toString());
        assertSame(error, assertThrows(AccessDeniedException.class,
                () -> new DirectorySync(false).force(temp, p -> { throw error; }, s -> fail(s))));
    }

    @Test void customProviderOpenDenialIsNotSuppressed() throws Exception {
        try (var fs = FileSystems.newFileSystem(temp.resolve("test.zip"), Map.of("create", "true"))) {
            var error = new AccessDeniedException("/");
            assertSame(error, assertThrows(AccessDeniedException.class,
                    () -> new DirectorySync(true).force(fs.getPath("/"), p -> { throw error; }, s -> fail(s))));
        }
    }

    @Test void windowsOtherOpenErrorsAreNotSuppressed() {
        var error = new IOException("disk failure");
        assertSame(error, assertThrows(IOException.class,
                () -> new DirectorySync(true).force(temp, p -> { throw error; }, s -> fail(s))));
    }

    @Test void windowsForceDenialIsNotSuppressedAndHandleCloses() {
        var error = new AccessDeniedException(temp.toString());
        AtomicInteger closes = new AtomicInteger();
        assertSame(error, assertThrows(AccessDeniedException.class,
                () -> new DirectorySync(true).force(temp, p -> new DirectorySync.Handle() {
                    public void force() throws IOException { throw error; }
                    public void close() { closes.incrementAndGet(); }
                }, s -> fail(s))));
        assertEquals(1, closes.get());
    }

    @Test void windowsCloseErrorIsNotSuppressed() {
        var error = new IOException("close failure");
        assertSame(error, assertThrows(IOException.class,
                () -> new DirectorySync(true).force(temp, p -> new DirectorySync.Handle() {
                    public void force() {}
                    public void close() throws IOException { throw error; }
                }, s -> fail(s))));
    }

    @Test void successfulSyncStillForcesAndClosesAfterPriorDenial() throws Exception {
        DirectorySync sync = new DirectorySync(true);
        sync.force(temp, p -> { throw new AccessDeniedException(p.toString()); }, s -> {});
        AtomicInteger forces = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        sync.force(temp, p -> new DirectorySync.Handle() {
            public void force() { forces.incrementAndGet(); }
            public void close() { closes.incrementAndGet(); }
        }, s -> fail(s));
        assertEquals(1, forces.get());
        assertEquals(1, closes.get());
    }

    @Test void realDirectorySyncOnSupportedHost() throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            new DirectorySync().force(temp, s -> fail(s));
        }
    }
}
