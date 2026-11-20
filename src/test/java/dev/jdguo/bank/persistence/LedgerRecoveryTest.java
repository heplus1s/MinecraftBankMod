package dev.jdguo.bank.persistence;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LedgerRecoveryTest {
    @TempDir Path dir;
    final BankConfig config=BankConfig.defaults();
    @Test void newestCheckpointWinsWithoutDiscardingBalances() throws Exception {
        var old=BankBooks.create(config);var newer=old.copyExact();UUID player=UUID.randomUUID();
        newer.creditHeld(player,"note",new BigDecimal("123.45"));newer.advanceCheckpoint();
        Path sidecar=dir.resolve("ledger.json");Files.writeString(sidecar,newer.toJson());
        assertEquals(newer.toJson(),LedgerRecovery.recover(old.toJson(),sidecar,false,config).toJson());
        Files.writeString(sidecar,old.toJson());
        assertEquals(newer.toJson(),LedgerRecovery.recover(newer.toJson(),sidecar,false,config).toJson());
    }
    @Test void corruptOrMissingNbtRecoversSidecarButNeverSilentlyCreates() throws Exception {
        var books=BankBooks.create(config);Path sidecar=dir.resolve("ledger.json");Files.writeString(sidecar,books.toJson());
        assertEquals(books.toJson(),LedgerRecovery.recover(null,sidecar,false,config).toJson());
        assertEquals(books.toJson(),LedgerRecovery.recover("broken",sidecar,false,config).toJson());
        Files.writeString(sidecar,"{}");
        assertThrows(IllegalStateException.class,()->LedgerRecovery.recover(null,sidecar,true,config));
        assertThrows(IllegalStateException.class,()->LedgerRecovery.recover("{}",sidecar,false,config));
        Files.delete(sidecar);
        assertThrows(IllegalStateException.class,()->LedgerRecovery.recover(null,sidecar,false,config));
        assertThrows(IllegalStateException.class,()->LedgerRecovery.recover("",sidecar,false,config));
        assertNotNull(LedgerRecovery.recover(null,sidecar,true,config));
    }
    @Test void corruptSidecarDoesNotHideValidLegacyWorldSnapshot() throws Exception {
        var books=BankBooks.create(config);Path sidecar=dir.resolve("ledger.json");Files.writeString(sidecar,"truncated");
        assertEquals(books.toJson(),LedgerRecovery.recover(books.toJson(),sidecar,false,config).toJson());
    }
}
