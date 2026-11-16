package dev.jdguo.bank.net;

import dev.jdguo.bank.persistence.BankRuntime;
import dev.jdguo.bank.ledger.TxResult;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import java.math.BigDecimal;
import java.util.UUID;

/** Account redemption only: physical items remain in custody until ordinary integer withdrawal. */
public final class PersonalRedemptionService {
    private PersonalRedemptionService() {}
    public static boolean redeem(ServerPlayerEntity player, String name, BigDecimal quantity) {
        var note = BankRuntime.books().personalNoteByName(name);
        if (note == null) { player.sendMessage(Text.literal("没有该个人货币。"), false); return false; }
        String reference = "personal-redeem:" + UUID.randomUUID();
        TxResult result = BankRuntime.transact(book -> book.redeemPersonalToSavings(player.getUuid(), note.id(), quantity, reference));
        if (!result.success()) {
            player.sendMessage(Text.literal("未兑付：" + result.code()), false);
            return false;
        }
        var receipt = BankRuntime.books().personalSettlementReceipt(reference);
        String currency = receipt.get("currency").getAsString();
        var def = BankRuntime.books().currency(currency);
        player.sendMessage(Text.literal("已按实际抵押物兑付到个人储蓄：" + receipt.get("amount").getAsString()
                + " " + (def == null ? currency : def.name()) + "。实物可在存取页按整数提取。"), false);
        return true;
    }
    public static void paperInstructions(ServerPlayerEntity player) {
        player.sendMessage(Text.literal("请先在存入页把个人纸币存回该币账户，再选择兑付；底层实物小数权益进入个人储蓄，不直接给物品。"), false);
    }
}
