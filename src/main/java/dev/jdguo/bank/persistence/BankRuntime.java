package dev.jdguo.bank.persistence;

import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.ledger.BankBooks;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BankRuntime {
    public static final Logger LOGGER = LoggerFactory.getLogger("bankmod");

    public static BankConfig config = BankConfig.defaults();
    public static BankWorldState state;
    public static MinecraftServer server;

    private BankRuntime() {}

    public static BankBooks books() {
        return state.books();
    }

    public static void save() {
        if (state != null) {
            state.markDirty();
            state.writeBackup();
        }
    }
}
