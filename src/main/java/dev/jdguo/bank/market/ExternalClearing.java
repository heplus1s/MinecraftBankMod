package dev.jdguo.bank.market;

import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.persistence.BankRuntime;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import java.math.BigDecimal;
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
