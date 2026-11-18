package dev.jdguo.bank.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.persistence.BankRuntime;
import java.math.BigDecimal;
import java.util.UUID;

/** Server-thread integration. Persist the passage reference BEFORE calling; retry identical
 * arguments after uncertain outcomes. Success means ledger persistence, not vehicle delivery.
 * TxResult.amount is legacy whole units: never use it to reconstruct the exact charged amount.
 */
public final class TollSettlement {
    private TollSettlement() {}
    /** No local-file fallback is safe after a bank-authoritative payload has been observed. */
    public static String loadState() {
        if (BankRuntime.state == null || BankRuntime.server == null || !BankRuntime.server.isOnThread())
            return "{\"success\":false,\"code\":\"bank_not_ready\"}";
        if (BankRuntime.books().tollStateMissingAfterPayments()) return "{\"success\":false,\"code\":\"state_missing\"}";
        return BankRuntime.books().tollState().toString();
    }
    public static TxResult saveState(long expectedRevision, String payload) {
        return BankRuntime.transact(b -> b.saveTollState(expectedRevision, payload));
    }
    public static String currencies() {
        JsonArray result = new JsonArray();
        if (BankRuntime.state == null || BankRuntime.server == null || !BankRuntime.server.isOnThread()) return result.toString();
        BankRuntime.books().currencies().forEach(c -> add(result, c.id(), c.name()));
        BankRuntime.books().personalNotes().forEach(c -> add(result, "personal:" + c.id(), c.name()));
        return result.toString();
    }
    private static void add(JsonArray rows, String id, String name) {
        JsonObject row = new JsonObject(); row.addProperty("id", id); row.addProperty("name", name); rows.add(row);
    }
    public static TxResult transfer(UUID from, UUID to, String currency, BigDecimal amount, String reference) {
        return BankRuntime.transact(b -> b.tollPayment(from, to, currency, amount, reference, false));
    }
    public static TxResult collectSystem(UUID payer, String currency, BigDecimal amount, String reference) {
        return BankRuntime.transact(b -> b.tollPayment(payer, null, currency, amount, reference, true));
    }
    public static TxResult previewSystem(String currency, BigDecimal amount) {
        if (BankRuntime.state == null || BankRuntime.server == null || !BankRuntime.server.isOnThread()) return TxResult.fail("bank_not_ready", "");
        return BankRuntime.books().copyExact().previewTollSystem(currency, amount);
    }
    public static TxResult receipt(String reference) {
        if (BankRuntime.state == null || BankRuntime.server == null || !BankRuntime.server.isOnThread()) return TxResult.fail("bank_not_ready", "");
        return BankRuntime.books().tollReceipt(reference);
    }
}
