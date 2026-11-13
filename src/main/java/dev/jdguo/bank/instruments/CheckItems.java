package dev.jdguo.bank.instruments;

import dev.jdguo.bank.currency.CurrencyDef;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.CompactFormat;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.text.Text;

import java.math.BigInteger;
import java.util.UUID;

/** Vanilla written book. Generation 3 (tattered) so the crafting grid will not copy it. */
public final class CheckItems {
    public static final String TAG = "bankmod_check";

    private CheckItems() {}

    public static ItemStack create(BankBooks books, CheckRecord record) {
        CurrencyDef currency = books.currency(record.currency());
        String name = currency == null ? record.currency() : currency.name();
        String amount = CompactFormat.format(record.amount());
        String title = trim(name + " " + amount, 32);
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.setCustomName(Text.literal(title).styled(style -> style.withItalic(false)));
        NbtCompound nbt = stack.getOrCreateNbt();
        nbt.putString("title", title);
        nbt.putString("author", "中央银行");
        nbt.putInt("generation", 3);
        nbt.putBoolean("resolved", true);
        String payee = record.payee() == null ? "持票人" : record.payee().toString();
        Text page = Text.literal("中央银行汇票\n\n币种: " + name
                + "\n金额: " + record.amount().toString()
                + "\n显示: " + amount
                + "\n出票: " + record.issuerName()
                + "\n抬头: " + payee
                + "\n编号: " + record.id()
                + "\n\n凭此票向网点兑付一次。复制件无效。");
        NbtList pages = new NbtList();
        pages.add(NbtString.of(Text.Serializer.toJson(page)));
        nbt.put("pages", pages);
        NbtCompound body = new NbtCompound();
        body.putString("id", record.id().toString());
        body.putString("currency", record.currency());
        body.putString("amount", record.amount().toString());
        body.putString("issuer", record.issuer().toString());
        body.putString("payee", record.payee() == null ? "" : record.payee().toString());
        nbt.put(TAG, body);
        return stack;
    }

    public static PresentedCheck read(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasNbt() || !stack.getNbt().contains(TAG)) {
            return null;
        }
        NbtCompound body = stack.getNbt().getCompound(TAG);
        try {
            String payee = body.getString("payee");
            return new PresentedCheck(
                    UUID.fromString(body.getString("id")),
                    body.getString("currency"),
                    new BigInteger(body.getString("amount")),
                    UUID.fromString(body.getString("issuer")),
                    payee.isBlank() ? null : UUID.fromString(payee));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String trim(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}
