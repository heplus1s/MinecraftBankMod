package dev.jdguo.bank.market;

import dev.jdguo.bank.currency.CurrencyDef;
import dev.jdguo.bank.currency.CurrencyKind;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.persistence.BankRuntime;
import dev.jdguo.bank.text.BankPhrases;
import dev.jdguo.bank.text.BankTalk;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Once a second, commodity items on a bankrupt issuer are taken and booked against their debt. */
public final class IssuerLevy {
    private IssuerLevy() {}

    public static void tick(MinecraftServer server) {
        if (BankRuntime.books() == null || !DebtSeizure.due(server.getTicks())) {
            return;
        }
        BankBooks books = BankRuntime.books();
        boolean changed = false;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!books.owesTheBank(player.getUuid())) {
                continue;
            }
            Map<String, BigInteger> available = availableCommodities(player, books);
            Map<String, BigInteger> taken = books.seizeCommodities(player.getUuid(), available);
            if (taken.isEmpty()) {
                continue;
            }
            removeCommodities(player, books, taken);
            changed = true;
            player.sendMessage(BankTalk.text(player, BankPhrases.debtSeized(
                    list(books, taken, true), list(books, taken, false),
                    debts(books, player.getUuid(), true), debts(books, player.getUuid(), false))), false);
        }
        if (changed) {
            BankRuntime.save();
        }
    }

    private static Map<String, BigInteger> availableCommodities(ServerPlayerEntity player, BankBooks books) {
        Map<String, BigInteger> available = new LinkedHashMap<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (slot >= 36 && slot <= 39) {
                continue;
            }
            addStack(books, available, inventory.getStack(slot));
        }
        Box box = player.getBoundingBox().expand(8.0);
        for (ItemEntity entity : player.getWorld().getEntitiesByClass(ItemEntity.class, box, entity -> true)) {
            addStack(books, available, entity.getStack());
        }
        return available;
    }

    private static void addStack(BankBooks books, Map<String, BigInteger> available, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        CurrencyDef def = books.byItem(Registries.ITEM.getId(stack.getItem()).toString());
        if (def == null || def.kind() != CurrencyKind.COMMODITY) {
            return;
        }
        available.merge(def.id(), BigInteger.valueOf(stack.getCount()), BigInteger::add);
    }

    private static void removeCommodities(ServerPlayerEntity player, BankBooks books, Map<String, BigInteger> taken) {
        Map<String, BigInteger> left = new LinkedHashMap<>(taken);
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size() && owes(left); slot++) {
            if (slot >= 36 && slot <= 39) {
                continue;
            }
            inventory.setStack(slot, shrink(books, left, inventory.getStack(slot)));
        }
        if (!owes(left)) {
            return;
        }
        Box box = player.getBoundingBox().expand(8.0);
        List<ItemEntity> entities = new ArrayList<>(player.getWorld().getEntitiesByClass(ItemEntity.class, box, entity -> true));
        for (ItemEntity entity : entities) {
            if (!owes(left)) {
                return;
            }
            ItemStack stack = shrink(books, left, entity.getStack());
            if (stack.isEmpty()) {
                entity.discard();
            } else {
                entity.setStack(stack);
            }
        }
    }

    private static ItemStack shrink(BankBooks books, Map<String, BigInteger> left, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        CurrencyDef def = books.byItem(Registries.ITEM.getId(stack.getItem()).toString());
        if (def == null) {
            return stack;
        }
        BigInteger need = left.getOrDefault(def.id(), BigInteger.ZERO);
        if (need.signum() <= 0) {
            return stack;
        }
        int take = need.min(BigInteger.valueOf(stack.getCount())).intValue();
        left.put(def.id(), need.subtract(BigInteger.valueOf(take)));
        stack.decrement(take);
        return stack.isEmpty() ? ItemStack.EMPTY : stack;
    }

    private static boolean owes(Map<String, BigInteger> left) {
        for (BigInteger amount : left.values()) {
            if (amount.signum() > 0) {
                return true;
            }
        }
        return false;
    }

    private static String list(BankBooks books, Map<String, BigInteger> amounts, boolean chinese) {
        List<String> parts = new ArrayList<>();
        amounts.forEach((currency, amount) -> {
            if (amount.signum() <= 0) {
                return;
            }
            CurrencyDef def = books.currency(currency);
            String name = def == null ? currency : (chinese ? def.name() : def.nameEn());
            parts.add(name + " ×" + amount);
        });
        return parts.isEmpty() ? (chinese ? "无" : "nothing") : String.join(chinese ? "、" : ", ", parts);
    }

    private static String debts(BankBooks books, java.util.UUID player, boolean chinese) {
        List<String> parts = new ArrayList<>();
        for (CurrencyDef def : books.currencies()) {
            BigInteger amount = books.issuerDebt(player, def.id());
            if (amount.signum() <= 0) {
                continue;
            }
            parts.add((chinese ? def.name() : def.nameEn()) + " ×" + amount);
        }
        return parts.isEmpty() ? "0" : String.join(chinese ? "、" : ", ", parts);
    }
}
