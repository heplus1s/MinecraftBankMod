package dev.jdguo.bank.net;

import com.google.gson.JsonObject;
import dev.jdguo.bank.ledger.CentralAdjustment;
import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.persistence.BankRuntime;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** One-shot, short-lived admin previews, revalidated inside the durable transaction. */
final class CentralAdjustmentUi {
    private CentralAdjustmentUi() {}
    static void handle(ServerPlayerEntity player, BankUiNetwork.Session session, String[] parts) {
        if (!session.admin || !player.hasPermissionLevel(2)) return;
        if ("admin-adjust-cancel".equals(parts[0])) { session.centralAdjustment = null; session.centralAdjustmentToken = ""; return; }
        if ("admin-adjust-preview".equals(parts[0])) {
            session.centralAdjustment = null; session.centralAdjustmentToken = "";
            if (parts.length != 4) return;
            session.centralAdjustment = CentralAdjustment.preview(BankRuntime.books(),parts[1],parts[2],parts[3]);
            session.centralAdjustmentExpires = System.currentTimeMillis()+60_000L;
            session.centralAdjustmentToken = java.util.UUID.randomUUID().toString();
            return;
        }
        if (parts.length != 2 || !parts[1].equals(session.centralAdjustmentToken)) {
            player.sendMessage(Text.literal("确认与当前预览不匹配，请重新预览"),false); return;
        }
        CentralAdjustment approved = session.centralAdjustment;
        session.centralAdjustment = null; session.centralAdjustmentToken = "";
        if (approved == null || System.currentTimeMillis() > session.centralAdjustmentExpires) {
            player.sendMessage(Text.literal("预览已过期，请重新预览"),false); return;
        }
        TxResult result = BankRuntime.transact(approved::confirm);
        player.sendMessage(Text.literal(result.success()?"已按预览完成调整":result.message()),false);
    }
    static void addPreview(JsonObject payload, BankUiNetwork.Session session) {
        if (!session.admin || session.centralAdjustment == null) return;
        JsonObject j = session.centralAdjustment.json();
        j.addProperty("token",session.centralAdjustmentToken);
        if(System.currentTimeMillis()>session.centralAdjustmentExpires) { j.addProperty("allowed",false);j.addProperty("reason","预览已过期，请重新预览"); }
        payload.add("centralAdjustmentPreview",j);
    }
}
