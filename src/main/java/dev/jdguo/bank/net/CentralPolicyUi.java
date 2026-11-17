package dev.jdguo.bank.net;

import com.google.gson.JsonObject;
import dev.jdguo.bank.ledger.CentralIssuePlan;
import dev.jdguo.bank.ledger.Money;
import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.persistence.BankRuntime;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** Per-session, expiring, one-use operator previews. */
final class CentralPolicyUi {
    private CentralPolicyUi() { }

    static void handle(ServerPlayerEntity player, BankUiNetwork.Session session, String[] parts) {
        if (!session.admin || !player.hasPermissionLevel(2)) return;
        if ("admin-policy-cancel".equals(parts[0])) {
            session.centralIssuePlan = null;
            return;
        }
        if ("admin-policy-preview".equals(parts[0])) {
            session.centralIssuePlan = null;
            if (parts.length != 2) return;
            try {
                session.centralIssuePlan = BankRuntime.books().previewCentralIssue(Money.parse(parts[1]));
                session.centralIssueExpires = System.currentTimeMillis() + 60_000L;
            } catch (RuntimeException invalid) {
                player.sendMessage(Text.literal("请输入有效的目标汇率，单位为钻石 / 1 央行券"), false);
            }
            return;
        }
        CentralIssuePlan plan = session.centralIssuePlan;
        session.centralIssuePlan = null; // Consume before executing, including failures/replayed packets.
        if (plan == null || System.currentTimeMillis() > session.centralIssueExpires) {
            player.sendMessage(Text.literal("预览已失效，请重新预览"), false);
            return;
        }
        TxResult result = BankRuntime.transact(books -> books.confirmCentralIssue(player.getUuid(), plan));
        player.sendMessage(Text.literal(result.success() ? "已按确认的预览完成发行" : result.message()), false);
    }

    static void addPreview(JsonObject snapshot, BankUiNetwork.Session session) {
        if (!session.admin || session.centralIssuePlan == null) return;
        CentralIssuePlan plan = session.centralIssuePlan;
        JsonObject preview = new JsonObject();
        preview.addProperty("allowed", plan.allowed() && System.currentTimeMillis() <= session.centralIssueExpires);
        preview.addProperty("reason", plan.reason());
        preview.addProperty("amount", plan.amount().toString());
        if (plan.target() != null) preview.addProperty("target", plan.target().toPlainString());
        preview.addProperty("reserves", Money.display(plan.reserveValue()));
        preview.addProperty("liabilitiesAfter", Money.display(plan.liabilitiesAfter()));
        if (plan.resultingRate() != null) preview.addProperty("rate", Money.display(plan.resultingRate()));
        snapshot.add("centralPolicyPreview", preview);
    }
}
