package dev.jdguo.bank.net;

import com.google.gson.JsonObject;
import dev.jdguo.bank.ledger.PersonalQuantityPlan;
import dev.jdguo.bank.persistence.BankRuntime;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.UUID;

/** Short-lived quote scoped to the live player's bank session. */
final class PersonalQuantityUi {
    private PersonalQuantityUi() {}

    static void handle(ServerPlayerEntity player, BankUiNetwork.Session session, String[] args) {
        if (session.admin) { clear(session); return; }
        if ("note-quantity-cancel".equals(args[0])) { clear(session); return; }
        if ("note-quantity-preview".equals(args[0])) {
            clear(session);
            if (args.length != 3) return;
            try {
                session.personalQuantityPlan = PersonalQuantityPlan.preview(BankRuntime.books(), player.getUuid(), args[1], args[2]);
            } catch (RuntimeException unavailable) {
                player.sendMessage(Text.literal("暂时无法读取有效账本，请稍后重新预览。"), false);
                return;
            }
            if (session.personalQuantityPlan.allowed()) session.personalQuantityToken = UUID.randomUUID().toString();
            session.personalQuantityExpires = System.currentTimeMillis() + 60_000L;
            return;
        }
        if (!"note-quantity-confirm".equals(args[0])) return;
        PersonalQuantityPlan plan = session.personalQuantityPlan;
        if (args.length != 2 || plan == null || !plan.allowed() || session.personalQuantityToken.isEmpty()
                || !session.personalQuantityToken.equals(args[1]) || System.currentTimeMillis() >= session.personalQuantityExpires) {
            player.sendMessage(Text.literal("未执行：预览不匹配或已过期，请重新预览。"), false);
            return;
        }
        clear(session);
        var result = BankRuntime.transact(book -> plan.confirm(book, player.getUuid()));
        player.sendMessage(Text.literal(result.message()), false);
    }

    private static void clear(BankUiNetwork.Session session) {
        session.personalQuantityPlan = null;
        session.personalQuantityToken = "";
        session.personalQuantityExpires = 0;
    }

    static void addPreview(JsonObject payload, BankUiNetwork.Session session) {
        if (session.admin) return;
        payload.addProperty("personalQuantitySupported", true);
        if (session.personalQuantityPlan == null) return;
        JsonObject data = session.personalQuantityPlan.json();
        if (System.currentTimeMillis() >= session.personalQuantityExpires) {
            data.addProperty("allowed", false);
            data.addProperty("reason", "预览已过期，请重新预览。");
        } else if (session.personalQuantityPlan.allowed()) {
            data.addProperty("token", session.personalQuantityToken);
        }
        payload.add("personalQuantityPreview", data);
    }
}
