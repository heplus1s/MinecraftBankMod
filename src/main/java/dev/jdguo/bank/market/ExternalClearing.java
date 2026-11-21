package dev.jdguo.bank.market;

import com.google.gson.JsonObject;
import dev.jdguo.bank.instruments.CheckItems;
import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.persistence.BankRuntime;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

/** API only for trusted installed server-side mods. Never expose these methods directly to client payloads. */
public final class ExternalClearing {
    private ExternalClearing(){}
    public static boolean ready(){
        return BankRuntime.state!=null && BankRuntime.server!=null && BankRuntime.server.isOnThread();
    }
    private static boolean partner(String id){return id!=null && id.matches("[a-z][a-z0-9_-]{1,31}") && FabricLoader.getInstance().isModLoaded(id);}
    public static boolean live(ServerPlayerEntity p){
        return ready()&&p!=null&&BankRuntime.server.getPlayerManager().getPlayer(p.getUuid())==p;
    }
    public static String quote(String mod,ServerPlayerEntity p, String currency,BigDecimal debit){
        return partner(mod)&&live(p)?BankRuntime.books().quoteExternalPurchase(p.getUuid(),currency,debit).toString():"{\"ok\":false}";
    }
    // The integrating mod must obtain explicit player confirmation for the displayed quote and its own goods.
    public static TxResult pay(String mod,ServerPlayerEntity p,String currency,BigDecimal debit,String fingerprint,String reference) {
        return partner(mod)&&live(p)? BankRuntime.transact(b->b.payExternalPurchase(mod,p.getUuid(),currency,debit,fingerprint,reference))
                :TxResult.fail("permission","外部结算权限不可用");
    }
    /** Read-only check of the original registered note in the player's main hand. */
    public static String quoteBill(String mod,ServerPlayerEntity player){
        if(!partner(mod)||!live(player)){
            var out=new JsonObject();out.addProperty("ok",false);out.addProperty("message","外部结算权限不可用");
            return out.toString();
        }
        return BankRuntime.books().quoteExternalBill(player.getUuid(),CheckItems.read(player.getMainHandStack())).toString();
    }

    /** Verify the same physical bill at confirmation and credit its full face value to this partner. */
    public static TxResult receiveBill(String mod,ServerPlayerEntity player,UUID quotedSerial,BigInteger quotedFace,String reference){
        if(!partner(mod)||!live(player))return TxResult.fail("permission","外部结算权限不可用");
        var hand=player.getMainHandStack();
        var bill=CheckItems.read(hand);
        if(bill==null||!bill.id().equals(quotedSerial)||!bill.amount().equals(quotedFace))
            return TxResult.fail("stale_quote","主手汇票已变化，请重新报价");
        var saved=BankRuntime.transact(b->b.payExternalBill(mod,player.getUuid(),bill,new BigDecimal(bill.amount()),reference));
        // Only remove the item after the durable registry commit. A leftover copy cannot settle again.
        if(saved.success()){
            hand.decrement(1);player.getInventory().markDirty();
        }
        return saved;
    }

    /** Reserve an authorized payout from the caller's balance as paper or a registered bearer bill. */
    public static TxResult issueInstrument(String mod,ServerPlayerEntity player,String mode,BigInteger amount,String reference){
        return partner(mod)&&live(player)
                ?BankRuntime.transact(b->b.prepareExternalInstrument(mod,player.getUuid(),amount,mode,reference))
                :TxResult.fail("permission","外部结算权限不可用");
    }

    /** Use only from the live account owner's explicit confirmation, never from a caller-supplied payer UUID.
     * Installed server mods are trusted to enforce that confirmation, as with pay(). The payee may be offline.
     */
    public static TxResult issueBillFromSavings(String mod,ServerPlayerEntity owner,UUID payee,BigInteger amount,String reference){
        return partner(mod)&&live(owner)
                ?BankRuntime.transact(b->b.prepareExternalSavingsBill(mod,owner.getUuid(),payee,amount,reference))
                :TxResult.fail("permission","外部结算权限不可用");
    }

    /** Deliver only an outstanding instrument belonging to this partner and this player. */
    public static String claimInstrument(String mod,ServerPlayerEntity player){
        return partner(mod)&&live(player)?ExternalInstrumentDelivery.claim(mod,player):"外部结算权限不可用";
    }

    public static boolean recorded(String mod,String ref){
        return ready()&&partner(mod)&&BankRuntime.books().externalTransferRecorded(mod,ref);
    }
    public static BigDecimal treasury(String mod,ServerPlayerEntity op){
        return partner(mod)&&live(op)&&op.hasPermissionLevel(2)?BankRuntime.books().externalTreasuryQuote():null;
    }
    public static TxResult move(String mod,ServerPlayerEntity op,boolean fund,BigDecimal amount,BigDecimal before,String ref){
        return partner(mod)&&live(op)&&op.hasPermissionLevel(2)
                ?BankRuntime.transact(b->b.moveExternalFunds(mod,op.getUuid(),fund,amount,before,ref))
                :TxResult.fail("permission","仅在线管理员可划拨");
    }
    /** Deliberately narrow: only Arcade's own cash may fund automatic taxes. */
    public static TxResult arcadeTax(BigDecimal amount,String ref){
        String mod="arcadehall";
        if(!ready()||!partner(mod))return TxResult.fail("permission","游艺厅税款接口不可用");
        return BankRuntime.transact(b->b.moveExternalFunds(mod,new UUID(0,0),false,amount,b.externalTreasuryQuote(),ref));
    }
    public static TxResult credit(String mod,ServerPlayerEntity p,BigDecimal amount,String ref){
        return partner(mod)&&live(p)?BankRuntime.transact(b->b.creditFromExternal(mod,p.getUuid(),amount,ref))
                :TxResult.fail("permission","外部兑回权限不可用");
    }
}
