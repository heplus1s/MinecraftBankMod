package dev.jdguo.bank.instruments;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.network.ServerPlayerEntity;

import java.math.BigInteger;
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

    public static PresentedNote read(ItemStack stack) {
        if (!tagged(stack)) {
            return null;
        }
        return readSignature(stack.getNbt());
    }

    static PresentedNote readSignature(NbtCompound root) {
        if (root == null || !root.contains(TAG)) return null;
        NbtCompound body = root.getCompound(TAG);
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

    /** Refresh only the interacted stack; never scan inventories or change its identity/quantity. */
    public static boolean refreshPersonalLabel(ItemStack stack, dev.jdguo.bank.ledger.BankBooks books) {
        return tagged(stack) && refreshPersonalSignature(stack.getNbt(), books);
    }

    static boolean refreshPersonalSignature(NbtCompound root, dev.jdguo.bank.ledger.BankBooks books) {
        PresentedNote paper = readSignature(root);
        if (paper == null || paper.central() || books == null) return false;
        var note = books.personalNoteById(paper.personalNote());
        if (note == null) return false;
        NbtCompound body = root.getCompound(TAG);
        if (note.name().equals(body.getString("label"))) return false;
        NbtCompound updated = signature(paper, note.name()).getCompound("display");
        NbtCompound display = root.getCompound("display");
        display.putString("Name", updated.getString("Name"));
        display.put("Lore", updated.get("Lore").copy());
        root.put("display", display);
        body.putString("label", note.name());
        return true;
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
