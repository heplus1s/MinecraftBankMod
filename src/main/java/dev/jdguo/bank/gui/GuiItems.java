package dev.jdguo.bank.gui;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.text.Text;

import java.util.List;

public final class GuiItems {
    public static final String TAG = "bankmod_gui";
    public static final String ACTION = "bankmod_action";

    private GuiItems() {}

    public static ItemStack marker(Item item, Text name, List<Text> lore, String action, int count) {
        ItemStack stack = new ItemStack(item, Math.max(1, Math.min(item.getMaxCount(), count)));
        stack.setCustomName(name.copy().styled(style -> style.withItalic(false)));
        NbtCompound nbt = stack.getOrCreateNbt();
        nbt.putBoolean(TAG, true);
        nbt.putString(ACTION, action);
        if (!lore.isEmpty()) {
            NbtList lines = new NbtList();
            for (Text line : lore) {
                lines.add(NbtString.of(Text.Serializer.toJson(line.copy().styled(style -> style.withItalic(false)))));
            }
            stack.getOrCreateSubNbt("display").put("Lore", lines);
        }
        return stack;
    }

    public static boolean isMarker(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.hasNbt() && stack.getNbt().getBoolean(TAG);
    }

    public static String action(ItemStack stack) {
        if (!isMarker(stack)) {
            return "";
        }
        return stack.getNbt().getString(ACTION);
    }
}
