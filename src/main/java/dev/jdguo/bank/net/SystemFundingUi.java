package dev.jdguo.bank.net;

import com.google.gson.JsonObject;
import dev.jdguo.bank.ledger.Money;
import dev.jdguo.bank.persistence.BankRuntime;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import java.math.BigDecimal;

/** Server-built, one-use and expiring funding preview. */
final class SystemFundingUi {
    static void handle(ServerPlayerEntity player, BankUiNetwork.Session session, String[] parts) {
        if (!session.admin || !player.hasPermissionLevel(2)) { session.fundingPreview = null; return; }
        JsonObject previous = session.fundingPreview;
        session.fundingPreview = null;
        if ("admin-funding-cancel".equals(parts[0])) return;
        try {
            if ("admin-funding-preview".equals(parts[0])) {
                if (parts.length != 3 || !("shop".equals(parts[1]) || "land".equals(parts[1]))) return;
                if (!parts[2].matches("[0-9]{1,24}(\\.[0-9]{1,4})?")) throw new IllegalArgumentException();
                BigDecimal amount = new BigDecimal(parts[2]);
                if (amount.signum() <= 0) throw new IllegalArgumentException();
                var books = BankRuntime.books();
                BigDecimal source = books.treasuryExact(books.primaryVirtual().id());
                BigDecimal target = books.systemFundingBalance(parts[1]);
                if (source.compareTo(amount) < 0) {
                    player.sendMessage(Text.literal("央行自有央行券不足，请先注资；不会动用玩家存款"), false); return;
                }
                JsonObject plan = new JsonObject();
                plan.addProperty("account", parts[1]); plan.addProperty("amount", Money.stored(amount));
                plan.addProperty("source", Money.stored(source)); plan.addProperty("target", Money.stored(target));
                plan.addProperty("sourceAfter", Money.stored(source.subtract(amount)));
                plan.addProperty("targetAfter", Money.stored(target.add(amount)));
                session.fundingPreview = plan; session.fundingExpires = System.currentTimeMillis() + 60_000L;
                return;
            }
            if (previous == null || System.currentTimeMillis() > session.fundingExpires) {
                player.sendMessage(Text.literal("拨款预览已失效，请重新预览"), false); return;
            }
            var result = BankRuntime.transact(book -> book.fundSystemAccount(player.getUuid(), player.hasPermissionLevel(2),
                    previous.get("account").getAsString(), Money.parse(previous.get("amount").getAsString()),
                    Money.parse(previous.get("source").getAsString()), Money.parse(previous.get("target").getAsString())));
            player.sendMessage(Text.literal(result.message()), false);
        } catch (RuntimeException invalid) {
            player.sendMessage(Text.literal("拨款未完成，请检查金额并重新预览"), false);
        }
    }
}
