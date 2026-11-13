package dev.jdguo.bank;

import dev.jdguo.bank.commands.BankCommands;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.currency.ConfigJson;
import dev.jdguo.bank.display.DisplayService;
import dev.jdguo.bank.gui.BankScreenHandler;
import dev.jdguo.bank.gui.BankMenus;
import dev.jdguo.bank.gui.GuiItems;
import dev.jdguo.bank.market.MarketHooks;
import dev.jdguo.bank.outlet.OutletPoint;
import dev.jdguo.bank.persistence.BankRuntime;
import dev.jdguo.bank.persistence.BankWorldState;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dedicated-server bank. Vanilla clients join with no mod and no resource pack.
 * A later market can implement {@link MarketHooks}; this build has no order book.
 */
public final class BankMod implements DedicatedServerModInitializer {
    private final DisplayService displays = new DisplayService();

    @Override
    public void onInitializeServer() {
        loadConfig();
        BankCommands.register(displays);

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() || hand != Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }
            if (BankRuntime.state == null) {
                return ActionResult.PASS;
            }
            var pos = hit.getBlockPos();
            String id = world.getRegistryKey().getValue().toString();
            OutletPoint outlet = BankRuntime.books().outletAtBlock(id, pos.getX(), pos.getY(), pos.getZ());
            if (outlet == null) {
                return ActionResult.PASS;
            }
            BankMenus.open(serverPlayer, outlet);
            return ActionResult.SUCCESS;
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            BankRuntime.server = server;
            BankRuntime.state = BankWorldState.get(server);
            BankRuntime.LOGGER.info("中央银行已装入 {} 种货币，手续费 {}，网点 {}",
                    BankRuntime.books().currencies().size(),
                    BankRuntime.books().feeFraction(),
                    BankRuntime.books().outlets().size());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (BankRuntime.state != null) {
                BankRuntime.state.markDirty();
                BankRuntime.state.writeBackup();
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (BankRuntime.state == null) {
                return;
            }
            displays.tick(server);
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player.currentScreenHandler instanceof BankScreenHandler handler) {
                    handler.tick();
                }
                Box box = player.getBoundingBox().expand(8.0);
                for (ItemEntity item : player.getWorld().getEntitiesByClass(ItemEntity.class, box,
                        entity -> GuiItems.isMarker(entity.getStack()))) {
                    item.discard();
                }
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> displays.onJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> displays.onQuit(handler.player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> displays.resync(newPlayer));
    }

    private static void loadConfig() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("bank.json");
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, ConfigJson.write(BankConfig.defaults()));
            }
            BankRuntime.config = ConfigJson.parse(Files.readString(path));
        } catch (Exception error) {
            BankRuntime.LOGGER.error("config/bank.json 读不了，改用内置默认", error);
            BankRuntime.config = BankConfig.defaults();
        }
    }
}
