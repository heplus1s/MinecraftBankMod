package dev.jdguo.bank.market;

import com.google.gson.JsonArray;
import dev.jdguo.bank.ledger.TxResult;
import com.google.gson.JsonObject;
import dev.jdguo.bank.persistence.BankRuntime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.google.gson.JsonParser;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.UUID;

/** Optional bridge. Backing and wallet commit in one BankRuntime transaction. */
public final class ArcadeClearing {
    private ArcadeClearing() {}
    private static final String MOD="arcadehall";
    private static ServerPlayerEntity player(UUID id){return ready()?BankRuntime.server.getPlayerManager().getPlayer(id):null;}
    public static boolean ready() {
        return BankRuntime.state!=null && BankRuntime.server!=null && BankRuntime.server.isOnThread();
    }
    public static String currencies() {
        var books=BankRuntime.books(); var out=new JsonArray(); var primary=books.primaryVirtual();
        if(primary!=null)out.add(currency(primary.id(),primary.name()));
        for(String id:new String[]{"diamond","copper"})if(books.currency(id)!=null)out.add(currency(id,books.currency(id).name()));
        for(var coin:books.personalNotes()){
            if(out.size()>=100)break;
            if(books.personalTradingAllowed(coin) && !coin.redemptionPaused())out.add(currency("personal:"+coin.id(),coin.name()));
        }
        return out.toString();
    }
    private static JsonObject currency(String id,String name){
        var row=new JsonObject(); row.addProperty("id",id);row.addProperty("name",name);return row;
    }
    public static String quote(UUID who,String coin,String amount) {
        return ExternalClearing.quote(MOD,player(who),coin,new BigDecimal(amount));
    }
    public static String exchange(UUID who,String coin,String amount,String fingerprint,String ref) {
        return result(ExternalClearing.pay(MOD,player(who),coin,new BigDecimal(amount),fingerprint,ref));
    }
    public static String treasury(UUID actor) {
        var balance=ExternalClearing.treasury(MOD,player(actor));return balance==null?null:balance.toPlainString();
    }
    public static boolean recorded(String reference) { return ExternalClearing.recorded(MOD,reference); }
    public static String move(UUID actor,boolean intoArcade,String amount,String treasuryBefore,String ref) {
        return result(ExternalClearing.move(MOD,player(actor),intoArcade,new BigDecimal(amount),new BigDecimal(treasuryBefore),ref));
    }
    public static String redeem(UUID actor,String amount,String ref) {
        return result(ExternalClearing.credit(MOD,player(actor),new BigDecimal(amount),ref));
    }
    public static String instrument(UUID who,String mode,String amount,String ref){
        var target=player(who);
        if(!ExternalClearing.live(target))return "permission";
        return result(BankRuntime.transact(b->b.prepareExternalInstrument(MOD,who,new BigDecimal(amount).toBigIntegerExact(),mode,ref)));
    }
    public static String claim(UUID who){
        var p=player(who);
        if(!ExternalClearing.live(p))return "银行尚未就绪";
        return ExternalInstrumentDelivery.claim(MOD,p);
    }
    public static String tax(String amount,String ref) {
        return result(ExternalClearing.arcadeTax(new BigDecimal(amount),ref));
    }
    private static String result(TxResult result) { return result.success()?"ok":result.code(); }
}
