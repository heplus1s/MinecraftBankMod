package dev.jdguo.bank.commands;

import dev.jdguo.bank.ledger.BankBooks;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.server.MinecraftServer;

import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;

/**
 * Opt-in. Reads the datapack's {@code copper} and {@code diamond} scoreboards and books them
 * as deposits. It does not run on its own, and it skips players it has already imported.
 */
public final class ScoreImporter {
    private ScoreImporter() {}

    public static int importAll(MinecraftServer server, BankBooks books) {
        Scoreboard scoreboard = server.getScoreboard();
        int count = 0;
        count += importObjective(server, books, scoreboard, "copper", "copper");
        count += importObjective(server, books, scoreboard, "diamond", "diamond");
        return count;
    }

    private static int importObjective(MinecraftServer server, BankBooks books, Scoreboard scoreboard,
                                       String objectiveName, String currency) {
        if (books.currency(currency) == null) {
            return 0;
        }
        ScoreboardObjective objective = scoreboard.getNullableObjective(objectiveName);
        if (objective == null) {
            return 0;
        }
        int count = 0;
        for (ScoreboardPlayerScore score : scoreboard.getAllPlayerScores(objective)) {
            String name = score.getPlayerName();
            int value = score.getScore();
            if (value <= 0 || name.startsWith("#")) {
                continue;
            }
            Optional<UUID> id = resolve(server, name);
            if (id.isEmpty() || !books.markImported(id.get())) {
                continue;
            }
            var result = books.deposit(id.get(), currency, BigInteger.valueOf(value));
            if (result.success()) {
                count++;
            }
        }
        return count;
    }

    private static Optional<UUID> resolve(MinecraftServer server, String name) {
        var online = server.getPlayerManager().getPlayer(name);
        if (online != null) {
            return Optional.of(online.getUuid());
        }
        return server.getUserCache().findByName(name).map(profile -> profile.getId());
    }
}
