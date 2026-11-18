package dev.jdguo.bank.net;

import com.google.gson.JsonObject;
import dev.jdguo.bank.ledger.BankBooks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.*;

/** Read-only, server-paged customer directory. Never resolves profiles over the network. */
final class AdminPlayerDirectory {
    private AdminPlayerDirectory() {}

    static final class CachedRoster {
        final Map<UUID, String> names = new HashMap<>();
        long scannedAt;
        boolean complete = true;
    }

    static JsonObject page(ServerPlayerEntity viewer, BankBooks books, String query, int page, CachedRoster cache) {
        if (!viewer.hasPermissionLevel(2)) throw new SecurityException("Operator required");
        var server = viewer.getServer();
        Map<UUID, String> names = new HashMap<>();
        for (UUID id : books.customerIds()) names.put(id, "未知玩家 (" + id.toString().substring(0, 8) + ")");
        long now = System.nanoTime();
        if (cache.scannedAt == 0 || now - cache.scannedAt > 30_000_000_000L) {
            cache.complete = true;
            cache.names.clear();
            var directory = server.getSavePath(WorldSavePath.PLAYERDATA);
            try (var files = Files.list(directory)) {
                files.filter(path -> path.getFileName().toString().endsWith(".dat")).forEach(path -> {
                    String file = path.getFileName().toString();
                    try {
                        UUID id = UUID.fromString(file.substring(0, file.length() - 4));
                        cache.names.putIfAbsent(id, "未知玩家 (" + id.toString().substring(0, 8) + ")");
                    } catch (IllegalArgumentException ignored) { }
                });
            } catch (NoSuchFileException emptyWorld) {
                // A new world may not have saved any player data yet.
            } catch (IOException | UncheckedIOException | SecurityException failure) {
                cache.complete = false;
            }
            cache.scannedAt = now;
        }
        names.putAll(cache.names);
        for (UUID id : names.keySet()) {
            if (server.getUserCache() != null)
                server.getUserCache().getByUuid(id).ifPresent(profile -> {
                    if (profile.getName() != null && !profile.getName().isBlank()) names.put(id, profile.getName());
                });
        }
        server.getPlayerManager().getPlayerList().forEach(player -> names.put(player.getUuid(), player.getGameProfile().getName()));
        JsonObject result = AdminPlayerSnapshot.page(books, names, query, page);
        result.addProperty("directoryComplete", cache.complete);
        return result;
    }
}
