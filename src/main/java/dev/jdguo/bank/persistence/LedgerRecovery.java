package dev.jdguo.bank.persistence;

import com.google.gson.JsonParser;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import java.nio.file.Files;
import java.nio.file.Path;

/** Select only a validated snapshot. Creation is permitted solely by the world-level absence check. */
final class LedgerRecovery {
    private LedgerRecovery() {}
    static BankBooks recover(String worldJson, Path sidecar, boolean allowCreate, BankConfig config) {
        BankBooks main = null, backup = null;
        Exception failure = null;
        if (worldJson != null) {
            try { main = parse(worldJson, config); }
            catch (RuntimeException error) { failure = error; }
        }
        boolean sidecarAbsent = sidecar == null || Files.notExists(sidecar);
        if (!sidecarAbsent) {
            try { backup = parse(Files.readString(sidecar), config); }
            catch (Exception error) { failure = error; }
        }
        if (backup != null && (main == null || backup.checkpoint() > main.checkpoint())) return backup;
        if (main != null) return main;
        if (allowCreate && worldJson == null && sidecarAbsent) return BankBooks.create(config);
        throw new IllegalStateException("银行存档无法读取，旁路 JSON 也不可用；为保护余额已停止加载", failure);
    }
    private static BankBooks parse(String json, BankConfig config) {
        var root = JsonParser.parseString(json).getAsJsonObject();
        // These three fields have always been emitted, including legacy snapshots. '{}' is not an empty bank.
        for (String key : new String[]{"currencies", "supplies", "accounts"})
            if (!root.has(key) || !root.get(key).isJsonObject()) throw new IllegalStateException("Incomplete bank ledger: " + key);
        if (root.getAsJsonObject("currencies").size() == 0 || root.getAsJsonObject("supplies").size() == 0)
            throw new IllegalStateException("Missing bank currency/supply ledger");
        return BankBooks.fromJson(json, config);
    }
}
