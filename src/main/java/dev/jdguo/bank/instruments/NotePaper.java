package dev.jdguo.bank.instruments;

import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.network.ServerPlayerEntity;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Vanilla paper. NBT is identical for the same series and label, so the pile stacks to 64.
 * Worth is face value times stack count. There is no per-sheet id.
 */
public final class NotePaper {
    public static final String TAG = "bankmod_note";

    private NotePaper() {}

    public static ItemStack create(PresentedNote note, String label) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.setNbt(signature(note, label));
        return stack;
    }

    public static NbtCompound signature(PresentedNote note, String label) {
        NbtCompound root = new NbtCompound();
        NbtCompound display = new NbtCompound();
        boolean personal = note.personalNote() != null;
        String name = personal ? "个人银行券「" + label + "」" : "银行券";
        display.putString("Name", text(name, null));
        NbtList lore = new NbtList();
        lore.add(NbtString.of(text(personal ? "个人银行券" : "中央银行", "gray")));
        lore.add(NbtString.of(text(personal ? "Personal note \"" + label + "\"" : "Central bank note", "dark_gray")));
        lore.add(NbtString.of(text("面额 " + note.face().toString(), "white")));
        lore.add(NbtString.of(text("同面额可叠放，复制无效", "dark_gray")));
        display.put("Lore", lore);
        root.put("display", display);
        NbtCompound body = new NbtCompound();
        body.putString("scope", personal ? "personal" : "central");
        body.putString("face", note.face().toString());
        if (personal) {
            body.putString("note", note.personalNote().toString());
            body.putString("label", label);
        } else {
            body.putString("currency", note.currency());
        }
        root.put(TAG, body);
        return root;
    }

    public static boolean tagged(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.hasNbt() && stack.getNbt().contains(TAG);
    }

    /** Face value of central paper, and face value of each personal series, in one inventory. */
    public static Held tally(Inventory inventory) {
        BigInteger central = BigInteger.ZERO;
        Map<UUID, BigInteger> personal = new LinkedHashMap<>();
        if (inventory == null) {
            return new Held(central, personal);
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            PresentedNote note = read(inventory.getStack(slot));
            if (note == null) {
                continue;
            }
            BigInteger value = note.face().multiply(BigInteger.valueOf(inventory.getStack(slot).getCount()));
            if (note.central()) {
                central = central.add(value);
            } else {
                personal.merge(note.personalNote(), value, BigInteger::add);
            }
        }
        return new Held(central, personal);
    }

    public record Held(BigInteger central, Map<UUID, BigInteger> personal) {}

    public static PresentedNote read(ItemStack stack) {
        if (!tagged(stack)) {
            return null;
        }
        NbtCompound body = stack.getNbt().getCompound(TAG);
        try {
            BigInteger face = new BigInteger(body.getString("face"));
            if (face.signum() <= 0) {
                return null;
            }
            String scope = body.getString("scope");
            if ("central".equals(scope)) {
                String currency = body.getString("currency");
                if (currency.isBlank()) {
                    return null;
                }
                return new PresentedNote(currency, null, face);
            }
            if ("personal".equals(scope)) {
                return new PresentedNote(null, UUID.fromString(body.getString("note")), face);
            }
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Fills existing piles of the same paper first, then offers new piles of at most 64. */
    public static void give(ServerPlayerEntity player, PresentedNote note, String label, BigInteger sheets) {
        if (sheets == null || sheets.signum() <= 0 || sheets.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            return;
        }
        ItemStack proto = create(note, label);
        var inventory = player.getInventory();
        int left = sheets.intValue();
        for (int slot = 0; slot < 36 && left > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !ItemStack.canCombine(stack, proto)) {
                continue;
            }
            int room = stack.getMaxCount() - stack.getCount();
            if (room <= 0) {
                continue;
            }
            int add = Math.min(room, left);
            stack.increment(add);
            inventory.setStack(slot, stack);
            left -= add;
        }
        while (left > 0) {
            int count = Math.min(proto.getMaxCount(), left);
            ItemStack stack = proto.copy();
            stack.setCount(count);
            inventory.offerOrDrop(stack);
            left -= count;
        }
    }

    private static String text(String value, String color) {
        String colorPart = color == null ? "" : ",\"color\":\"" + color + "\"";
        return "{\"text\":\"" + escape(value) + "\",\"italic\":false" + colorPart + "}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
