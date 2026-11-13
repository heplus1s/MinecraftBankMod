package dev.jdguo.bank.gui;

import dev.jdguo.bank.outlet.OutletPoint;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class BankMenus {
    private BankMenus() {}

    public static void open(ServerPlayerEntity player, OutletPoint outlet) {
        open(player, outlet.name(), false);
    }

    /** Operator management page, including central issuance. It does not have to be at a kiosk. */
    public static void openAdmin(ServerPlayerEntity player) {
        open(player, "管理", true);
    }

    private static void open(ServerPlayerEntity player, String titleName, boolean remoteAdmin) {
        SimpleInventory inventory = new SimpleInventory(54);
        Text title = Text.literal("中央银行 · " + titleName).formatted(Formatting.AQUA);
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, unused) -> new BankScreenHandler(syncId, playerInventory, inventory, titleName, remoteAdmin),
                title));
    }
}
