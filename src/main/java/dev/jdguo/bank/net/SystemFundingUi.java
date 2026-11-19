package dev.jdguo.bank.net;

import com.google.gson.JsonObject;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.Money;
import dev.jdguo.bank.persistence.BankRuntime;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import java.math.BigDecimal;
import java.util.UUID;

/** Server-built, one-use and expiring funding preview. */
final class SystemFundingUi {
    static void handle(ServerPlayerEntity player, BankUiNetwork.Session session, String[] parts) {
        if (!session.admin || !player.hasPermissionLevel(2)) { session.fundingPreview = null; return; }
        JsonObject previous = session.fundingPreview;
        session.fundingPreview = null;
        if ("admin-funding-cancel".equals(parts[0])) return;
        try {
            if ("admin-funding-preview".equals(parts[0])) {
                if ((parts.length != 3 && parts.length != 4) || !("shop".equals(parts[1]) || "land".equals(parts[1]))) return;
                String direction = parts.length == 4 ? parts[3] : "fund";
                if (!"fund".equals(direction) && !"recall".equals(direction)) return;
                if (!parts[2].matches("[0-9]{1,24}(\\.[0-9]{1,4})?")) throw new IllegalArgumentException();
                BigDecimal amount = new BigDecimal(parts[2]);
                if (amount.signum() <= 0) throw new IllegalArgumentException();
                String account = parts[1];
                var books = BankRuntime.books();
                var capacity = books.systemRecallCapacity(account);
                boolean recall = "recall".equals(direction);
                if (recall && capacity.available().compareTo(amount) < 0) {
                    player.sendMessage(Text.literal("可拨回央行券不足，不动用待结算款"), false); return;
                }
                if (!recall && capacity.treasury().compareTo(amount) < 0) {
                    player.sendMessage(Text.literal("央行自有央行券不足，请先注资；不会动用玩家存款"), false); return;
                }
                JsonObject plan = new JsonObject();
                plan.addProperty("account", account); plan.addProperty("direction", direction);
                if (parts.length == 4) plan.addProperty("token", UUID.randomUUID().toString());
                plan.addProperty("amount", Money.stored(amount));
                put(plan, "source", capacity.treasury()); put(plan, "target", capacity.ordinary());
                put(plan, "settled", capacity.settled()); put(plan, "ordinaryAvailable", capacity.ordinaryAvailable());
                put(plan, "settledAvailable", capacity.settledAvailable()); put(plan, "available", capacity.available());
                put(plan, "incomingHeld", capacity.incomingHeld());
                BigDecimal fromOrdinary = recall ? capacity.ordinaryAvailable().min(amount) : Money.ZERO;
                BigDecimal fromSettled = recall ? amount.subtract(fromOrdinary) : Money.ZERO;
                BigDecimal treasuryAfter = capacity.treasury().add(recall ? amount : amount.negate());
                BigDecimal ordinaryAfter = capacity.ordinary().add(recall ? fromOrdinary.negate() : amount);
                BigDecimal settledAfter = capacity.settled().subtract(fromSettled);
                put(plan, "sourceAfter", treasuryAfter); put(plan, "targetAfter", ordinaryAfter);
                put(plan, "treasuryAfter", treasuryAfter); put(plan, "ordinaryAfter", ordinaryAfter);
                put(plan, "settledAfter", settledAfter);
                put(plan, "availableAfter", ordinaryAfter.add(settledAfter).subtract(capacity.incomingHeld()).max(BigDecimal.ZERO));
                session.fundingPreview = plan; session.fundingExpires = System.currentTimeMillis() + 60_000L;
                return;
            }
            if (!"admin-funding-confirm".equals(parts[0]) || previous == null
                    || System.currentTimeMillis() > session.fundingExpires || !matches(previous, parts)) {
                player.sendMessage(Text.literal("划转预览已失效，请重新预览"), false); return;
            }
            String account = previous.get("account").getAsString();
            BigDecimal amount = get(previous, "amount");
            boolean recall = "recall".equals(previous.get("direction").getAsString());
            var result = BankRuntime.transact(book -> recall
                    ? book.recallSystemAccount(player.getUuid(), player.hasPermissionLevel(2), account, amount,
                            new BankBooks.SystemRecallCapacity(get(previous, "source"), get(previous, "target"),
                                    get(previous, "settled"), get(previous, "ordinaryAvailable"),
                                    get(previous, "settledAvailable"), get(previous, "available"), get(previous, "incomingHeld")))
                    : book.fundSystemAccount(player.getUuid(), player.hasPermissionLevel(2), account, amount,
                            get(previous, "source"), get(previous, "target")));
            player.sendMessage(Text.literal(result.message()), false);
        } catch (RuntimeException invalid) {
            player.sendMessage(Text.literal("划转未完成，请检查金额并重新预览"), false);
        }
    }
    static boolean matches(JsonObject preview, String[] parts) {
        return preview.has("token") ? parts.length == 2 && preview.get("token").getAsString().equals(parts[1])
                : parts.length == 1 && "fund".equals(preview.get("direction").getAsString());
    }
    private static void put(JsonObject json, String key, BigDecimal amount) {
        json.addProperty(key, Money.stored(amount));
    }
    private static BigDecimal get(JsonObject json, String key) {
        return Money.parse(json.get(key).getAsString());
    }
}
