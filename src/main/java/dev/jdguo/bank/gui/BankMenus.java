package dev.jdguo.bank.gui;

import dev.jdguo.bank.net.BankUiNetwork;
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
        if (BankUiNetwork.wantsUi(player)) {
            BankUiNetwork.open(player, titleName, remoteAdmin);
            return;
        }
        openChest(player, titleName, remoteAdmin, remoteAdmin
                ? BankScreenHandler.Page.MANAGEMENT
                : BankScreenHandler.Page.SAVINGS);
    }

    /** Vanilla 9×6 chest, opened on a chosen page. Used directly and by the optional UI for item moves. */
    public static void openChest(ServerPlayerEntity player, String titleName, boolean remoteAdmin, BankScreenHandler.Page page) {
        SimpleInventory inventory = new SimpleInventory(54);
        Text title = Text.literal("中央银行 · " + titleName).formatted(Formatting.AQUA);
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, unused) -> new BankScreenHandler(syncId, playerInventory, inventory, titleName, remoteAdmin, page),
                title));
    }
}
