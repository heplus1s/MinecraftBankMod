package dev.jdguo.bank.market;

import com.google.gson.JsonArray;
import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.instruments.CheckItems;
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
    /** Invert the existing executable, rounded collateral quote; never debit during search. */
    public static String quoteForNoteValue(UUID who,String coin,String targetText) {
        try {
            if(player(who)==null||coin==null||!coin.startsWith("personal:"))return reject("该币种不能按游戏点数量报价");
            var target=new BigDecimal(targetText);
            if(target.signum()<=0||target.scale()>4||target.precision()>15)return reject("兑换金额超出范围");
            var books=BankRuntime.books();
            var id=UUID.fromString(coin.substring("personal:".length()));
            var note=books.personalNoteById(id);
            var central=books.primaryVirtual();
            if(central==null||!books.personalTradingAllowed(note)||note.redemptionPaused())return reject("该个人币暂不可结算");
            long high=books.personalActiveExact(note).movePointRight(4)
                    .min(BigDecimal.valueOf(999_999_999_999_999L)).longValue();
            BigDecimal debit=findExactDebit(high,target,amount->{
                var backing=books.quotePersonalClearing(id,amount);
                if(!backing.success()){
                    return switch(backing.code()){
                        case "below_precision" -> BigDecimal.ZERO;
                        case "short_reserve" -> null; // above executable collateral capacity
                        default -> throw new IllegalArgumentException("个人币暂不可结算，未扣款");
                    };
                }
                BigDecimal fx=backing.currency().equals(central.id())?BigDecimal.ONE:books.unitsPer(central.id(),backing.currency());
                if(fx==null||fx.signum()<=0)throw new IllegalArgumentException("暂无可用中央牌价，未扣款");
                return backing.amount().multiply(fx).setScale(4,RoundingMode.DOWN);
            });
            if(debit==null)return reject("该个人币目前无法精确兑换所选游戏点；请调整数量或改用央行券。未扣款");
            String quoted=ExternalClearing.quote(MOD,player(who),coin,debit);
            var response=JsonParser.parseString(quoted).getAsJsonObject();
            if(response.has("message")&&response.get("message").getAsString().contains("可用个人币不足"))
                return reject("可用个人币不足：需 "+debit.stripTrailingZeros().toPlainString()+" "+note.name()+"，未扣款");
            return quoted;
        } catch(IllegalArgumentException|ArithmeticException e){
            return reject(e.getMessage()==null?"无法计算实扣金额，未扣款":e.getMessage());
        }
    }
    static BigDecimal findExactDebit(long high,BigDecimal target,java.util.function.Function<BigDecimal,BigDecimal> value){
        long low=1; BigDecimal exact=null;
        while(low<=high){
            long mid=low+(high-low)/2;BigDecimal amount=BigDecimal.valueOf(mid,4);
            BigDecimal actual=value.apply(amount);
            if(actual==null||actual.compareTo(target)>=0) {
                if(actual!=null&&actual.compareTo(target)==0)exact=amount;
                high=mid-1;
            }else low=mid+1;
        }
        return exact;
    }
    private static String reject(String message){
        var o=new JsonObject();o.addProperty("ok",false);o.addProperty("message",message);return o.toString();
    }
    public static String billQuote(UUID who){
        var p=player(who);
        return p==null?reject("银行账本未就绪"):BankRuntime.books()
                .quoteExternalBill(who,CheckItems.read(p.getMainHandStack())).toString();
    }
    public static String exchangeBill(UUID who,String serial,String cost,String reference){
        var p=player(who);
        if(p==null)return "permission";
        var hand=p.getMainHandStack();
        var bill=CheckItems.read(hand);
        if(bill==null||!bill.id().toString().equals(serial))return "stale_quote";
        var saved=BankRuntime.transact(b->b.payExternalBill(MOD,who,bill,new BigDecimal(cost),reference));
        // The durable redemption registry, rather than item deletion, prevents replay after a crash.
        if(saved.success()){
            hand.decrement(1);p.getInventory().markDirty();
        }
        return result(saved);
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
