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

    /** New economic operations use copy-on-write: failure never publishes partial balances. */
    public static dev.jdguo.bank.ledger.TxResult transact(
            java.util.function.Function<BankBooks, dev.jdguo.bank.ledger.TxResult> operation) {
        if (state == null || server == null || !server.isOnThread()) {
            return dev.jdguo.bank.ledger.TxResult.fail("bank_not_ready", "银行账本未就绪");
        }
        BankBooks candidate = BankBooks.fromJson(books().toJson(), config);
        final dev.jdguo.bank.ledger.TxResult result;
        try {
            result = operation.apply(candidate);
        } catch (RuntimeException invalid) {
            LOGGER.warn("银行事务校验失败，未改变账本", invalid);
            return dev.jdguo.bank.ledger.TxResult.fail("invalid_transaction", "交易校验失败");
        }
        if (!result.success()) return result;
        candidate.advanceCheckpoint();
        if (!state.commit(candidate)) {
            return dev.jdguo.bank.ledger.TxResult.fail("save_failed", "银行保存失败，交易未执行");
        }
        return result;
    }

    public static void save() {
        saveMarket();
    }

    public static boolean saveMarket() {
        if (state == null) {
            return false;
        }
        state.books().advanceCheckpoint();
        state.markDirty();
        return server == null || state.writeBackup();
    }
}
