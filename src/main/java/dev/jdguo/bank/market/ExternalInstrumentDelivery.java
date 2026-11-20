package dev.jdguo.bank.market;

import dev.jdguo.bank.instruments.CheckItems;
import dev.jdguo.bank.instruments.NotePaper;
import dev.jdguo.bank.instruments.PresentedNote;
import dev.jdguo.bank.persistence.BankRuntime;
import java.math.BigInteger;
import java.nio.file.Files;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

/** Durable delivery of an already registered Bank instrument. Never drops or reissues on uncertainty. */
final class ExternalInstrumentDelivery {
    private ExternalInstrumentDelivery(){}
    static String claim(String partner,ServerPlayerEntity p){
        var book=BankRuntime.books();
        var row=book.nextExternalInstrument(partner,p.getUuid());
        if(row==null)return "没有待领票据";
        String ref=row.get("ref").getAsString(), tag="bank_external_delivery_"+ref;
        BigInteger amount=new BigInteger(row.get("amount").getAsString());
        boolean cash="cash".equals(row.get("mode").getAsString());
        try{
            var playerPath=p.getServer().getSavePath(WorldSavePath.PLAYERDATA).resolve(p.getUuidAsString()+".dat");
            if(!Files.exists(playerPath))DurablePlayerInventory.save(p);
            var nbt=NbtIo.readCompressed(playerPath.toFile());
            if(nbt==null)return "库存凭证无法读取，请勿重复领取";
            var tags=nbt.getList("Tags",8);
            boolean disk=false;
            for(int i=0;i<tags.size();i++)if(tag.equals(tags.getString(i))){disk=true;break;}
            boolean memory=p.getCommandTags().contains(tag);
            if(disk){
                if(!memory||!DurablePlayerInventory.integratedReceiptConfirmed(p,tag))return "交付状态待核对，请勿重复领取";
            }else{
                if(memory||DurablePlayerInventory.integratedHostReceiptPresent(p,tag))return "交付状态待核对，请勿重复领取";
                int slot=-1;
                for(int i=0;i<36;i++)if(p.getInventory().getStack(i).isEmpty()){slot=i;break;}
                if(slot<0)return "背包没有空位；票据已保留，可回柜台领取";
                if(p.getCommandTags().size()>=1024)return "库存凭证空间不足，票据仍待领取";
                ItemStack paper;
                if(cash){
                    String currency=row.get("currency").getAsString();
                    paper=NotePaper.create(new PresentedNote(currency,null,amount),book.currency(currency).name());
                }else{
                    var check=book.check(java.util.UUID.fromString(row.get("instrument").getAsString()));
                    if(check==null)return "汇票尚待核对";
                    paper=CheckItems.create(book,check);
                }
                if(!p.addCommandTag(tag))return "交付状态待核对";
                p.getInventory().setStack(slot,paper);
                p.getInventory().markDirty();
                DurablePlayerInventory.save(p);
            }
            var saved=BankRuntime.transact(b->b.finishExternalInstrument(partner,p.getUuid(),ref));
            if(!saved.success())return "物品可能已到手，流水待核对；不会重新签发";
            p.removeScoreboardTag(tag);
            return "已交付 "+amount+" 央行券"+(cash?"纸钞":"汇票");
        }catch(Exception failure){
            BankRuntime.LOGGER.warn("外部柜台票据待恢复 {}",ref,failure);
            return "票据交付待核对，请勿重复领取";
        }
    }
}
