package dev.jdguo.bank.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.currency.ConfigJson;
import dev.jdguo.bank.display.DisplayService;
import dev.jdguo.bank.gui.BankMenus;
import dev.jdguo.bank.instruments.CheckItems;
import dev.jdguo.bank.ledger.Amounts;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.CompactFormat;
import dev.jdguo.bank.ledger.Supply;
import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.outlet.OutletPoint;
import dev.jdguo.bank.persistence.BankRuntime;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.regex.Pattern;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class BankCommands {
    private static final Pattern NEW_OUTLET_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");

    private BankCommands() {}

    public static void register(DisplayService displays) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                literal("bank")
                        .executes(ctx -> openNear(ctx.getSource()))
                        .then(literal("outlets").executes(ctx -> listOutlets(ctx.getSource())))
                        .then(literal("display")
                                .then(toggle("sidebar", (src, on) -> pref(src, on, null, null)))
                                .then(toggle("bossbar", (src, on) -> pref(src, null, on, null)))
                                .then(toggle("tab", (src, on) -> pref(src, null, null, on)))
                                .then(literal("global").requires(BankCommands::op)
                                        .then(toggle("sidebar", (src, on) -> global(src, "sidebar", on)))
                                        .then(toggle("bossbar", (src, on) -> global(src, "bossbar", on)))
                                        .then(toggle("tab", (src, on) -> global(src, "tab", on)))
                                        .then(toggle("hologram", (src, on) -> global(src, "hologram", on)))
                                        .then(toggle("join", (src, on) -> global(src, "join", on)))))
                        .then(literal("admin").requires(BankCommands::op).executes(ctx -> openAdmin(ctx.getSource())))
                        .then(literal("outlet").requires(BankCommands::op)
                                .then(literal("set").then(argument("name", StringArgumentType.string()).executes(ctx -> setOutlet(ctx.getSource(), displays, StringArgumentType.getString(ctx, "name"), false))))
                                .then(literal("move").then(argument("name", StringArgumentType.string()).executes(ctx -> setOutlet(ctx.getSource(), displays, StringArgumentType.getString(ctx, "name"), true))))
                                .then(literal("remove").then(argument("name", StringArgumentType.string()).executes(ctx -> removeOutlet(ctx.getSource(), displays, StringArgumentType.getString(ctx, "name")))))
                                .then(literal("tp").then(argument("name", StringArgumentType.string()).executes(ctx -> teleport(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                                .then(literal("radius").then(argument("name", StringArgumentType.string()).then(argument("blocks", DoubleArgumentType.doubleArg(1, 32)).executes(ctx -> radius(ctx.getSource(), StringArgumentType.getString(ctx, "name"), DoubleArgumentType.getDouble(ctx, "blocks"))))))
                                .then(literal("lift").then(argument("name", StringArgumentType.string()).then(argument("blocks", DoubleArgumentType.doubleArg(0.5, 16)).executes(ctx -> lift(ctx.getSource(), displays, StringArgumentType.getString(ctx, "name"), DoubleArgumentType.getDouble(ctx, "blocks"))))))
                                .then(literal("hologram").then(argument("name", StringArgumentType.string())
                                        .then(literal("on").executes(ctx -> hologram(ctx.getSource(), displays, StringArgumentType.getString(ctx, "name"), true)))
                                        .then(literal("off").executes(ctx -> hologram(ctx.getSource(), displays, StringArgumentType.getString(ctx, "name"), false)))))
                        )
                        .then(literal("credit").requires(BankCommands::op)
                                .then(literal("unbacked").then(playerAmount("unbacked")))
                                .then(literal("backed").then(playerAmount("backed")))
                                .then(literal("retire").then(playerAmount("retire")))
                                .then(literal("check").then(playerAmount("check"))))
                        .then(toggle("freeze", (src, on) -> {
                            books().setFrozen(on);
                            BankRuntime.save();
                            return ok(src, on ? "Note redemption halted" : "Note redemption restored");
                        }).requires(BankCommands::op))
                        .then(literal("fee").requires(BankCommands::op).then(argument("percent", DoubleArgumentType.doubleArg(0, 100)).executes(ctx -> {
                            books().setFeeFraction(BigDecimal.valueOf(DoubleArgumentType.getDouble(ctx, "percent")).movePointLeft(2));
                            BankRuntime.save();
                            return ok(ctx.getSource(), "Fee is now " + DoubleArgumentType.getDouble(ctx, "percent") + "%");
                        })))
                        .then(literal("alpha").requires(BankCommands::op)
                                .then(argument("currency", StringArgumentType.word())
                                        .then(argument("permille", StringArgumentType.word()).executes(ctx -> setAlpha(ctx.getSource(), StringArgumentType.getString(ctx, "currency"), StringArgumentType.getString(ctx, "permille"))))))
                        .then(literal("clamp").requires(BankCommands::op)
                                .then(literal("min").then(argument("value", StringArgumentType.word()).executes(ctx -> clamp(ctx.getSource(), true, StringArgumentType.getString(ctx, "value")))))
                                .then(literal("max").then(argument("value", StringArgumentType.word()).executes(ctx -> clamp(ctx.getSource(), false, StringArgumentType.getString(ctx, "value"))))))
                        .then(literal("reserve").requires(BankCommands::op)
                                .then(argument("currency", StringArgumentType.word())
                                        .then(argument("amount", StringArgumentType.word()).executes(ctx -> setReserve(ctx.getSource(), StringArgumentType.getString(ctx, "currency"), StringArgumentType.getString(ctx, "amount"))))))
                        .then(literal("supply").requires(BankCommands::op).executes(ctx -> supply(ctx.getSource())))
                        .then(literal("audit").requires(BankCommands::op).executes(ctx -> {
                            ctx.getSource().sendMessage(Text.literal(books().audit()).formatted(Formatting.GRAY));
                            return 1;
                        }))
                        .then(literal("reload").requires(BankCommands::op).executes(ctx -> reload(ctx.getSource())))
                        .then(literal("import-scores").requires(BankCommands::op).executes(ctx -> {
                            int count = ScoreImporter.importAll(ctx.getSource().getServer(), books());
                            BankRuntime.save();
                            return ok(ctx.getSource(), "Imported " + count + " scoreboard balances (once per player per currency)");
                        }))
        ));
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<ServerCommandSource, ?> playerAmount(String mode) {
        return argument("player", GameProfileArgumentType.gameProfile())
                .then(argument("currency", StringArgumentType.word())
                        .then(argument("amount", StringArgumentType.word()).executes(ctx -> credit(
                                ctx.getSource(),
                                mode,
                                GameProfileArgumentType.getProfileArgument(ctx, "player"),
                                StringArgumentType.getString(ctx, "currency"),
                                StringArgumentType.getString(ctx, "amount")))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> toggle(String name, Toggle toggle) {
        return literal(name)
                .then(literal("on").executes(ctx -> toggle.run(ctx.getSource(), true)))
                .then(literal("off").executes(ctx -> toggle.run(ctx.getSource(), false)));
    }

    private interface Toggle {
        int run(ServerCommandSource source, boolean on) throws com.mojang.brigadier.exceptions.CommandSyntaxException;
    }

    private static int openNear(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        String world = player.getWorld().getRegistryKey().getValue().toString();
        OutletPoint outlet = books().nearest(world, player.getX(), player.getY(), player.getZ(), 0);
        if (outlet == null) {
            info(source, books().outlets().isEmpty()
                    ? "No outlets yet. An operator should look at a block and run /bank outlet set <name>"
                    : "You aren't within an outlet. /bank outlets lists the coordinates");
            return 0;
        }
        BankMenus.open(player, outlet);
        return 1;
    }

    private static int openAdmin(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BankMenus.openAdmin(source.getPlayerOrThrow());
        return 1;
    }

    private static int listOutlets(ServerCommandSource source) {
        if (books().outlets().isEmpty()) {
            return info(source, "No outlets yet");
        }
        for (OutletPoint outlet : books().outlets()) {
            source.sendMessage(Text.literal(outlet.name() + "  " + outlet.world()
                    + "  " + outlet.x() + " " + outlet.y() + " " + outlet.z()
                    + "  radius " + outlet.radius()).formatted(Formatting.AQUA));
        }
        return books().outlets().size();
    }

    private static int setOutlet(ServerCommandSource source, DisplayService displays, String name, boolean mustExist) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!mustExist && !validNewOutletName(name)) {
            return fail(source, "Outlet names must be 1-32 characters: a letter or digit, then letters, digits, underscore, or hyphen.");
        }
        ServerPlayerEntity player = source.getPlayerOrThrow();
        OutletPoint existing = books().outlet(name);
        if (mustExist && existing == null) {
            return fail(source, "No outlet with that name");
        }
        if (!mustExist && existing != null) {
            return fail(source, "That name is already used. Move it with /bank outlet move");
        }
        BlockPos pos = lookingAt(player);
        String world = player.getWorld().getRegistryKey().getValue().toString();
        if (existing == null) {
            books().addOutlet(new OutletPoint(name, world, pos.getX(), pos.getY(), pos.getZ(),
                    BankRuntime.config.outletRadius(), 2.4, true, null));
        } else {
            displays.discardHologram(source.getServer(), existing);
            existing.relocate(world, pos.getX(), pos.getY(), pos.getZ());
        }
        BankRuntime.save();
        return ok(source, "Outlet " + name + " -> " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ". Right-click that block to open the counter.");
    }

    private static int removeOutlet(ServerCommandSource source, DisplayService displays, String name) {
        OutletPoint outlet = books().outlet(name);
        if (outlet == null) {
            return fail(source, "No outlet with that name");
        }
        displays.discardHologram(source.getServer(), outlet);
        books().removeOutlet(name);
        BankRuntime.save();
        return ok(source, "Removed outlet " + name);
    }

    private static int teleport(ServerCommandSource source, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        OutletPoint outlet = books().outlet(name);
        if (outlet == null) {
            return fail(source, "No outlet with that name");
        }
        ServerPlayerEntity player = source.getPlayerOrThrow();
        net.minecraft.server.world.ServerWorld target = null;
        for (var candidate : source.getServer().getWorlds()) {
            if (candidate.getRegistryKey().getValue().toString().equals(outlet.world())) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            return fail(source, "That outlet's dimension isn't loaded");
        }
        player.teleport(target, outlet.x() + 0.5, outlet.y() + 1.0, outlet.z() + 0.5, player.getYaw(), player.getPitch());
        return ok(source, "Teleported to " + name);
    }

    private static int radius(ServerCommandSource source, String name, double blocks) {
        OutletPoint outlet = books().outlet(name);
        if (outlet == null) {
            return fail(source, "No outlet with that name");
        }
        outlet.setRadius(blocks);
        BankRuntime.save();
        return ok(source, name + " radius is now " + blocks);
    }

    private static int lift(ServerCommandSource source, DisplayService displays, String name, double blocks) {
        OutletPoint outlet = books().outlet(name);
        if (outlet == null) {
            return fail(source, "No outlet with that name");
        }
        displays.discardHologram(source.getServer(), outlet);
        outlet.setLift(blocks);
        BankRuntime.save();
        return ok(source, name + " hologram height is now " + blocks + " blocks");
    }

    private static int hologram(ServerCommandSource source, DisplayService displays, String name, boolean on) {
        OutletPoint outlet = books().outlet(name);
        if (outlet == null) {
            return fail(source, "No outlet with that name");
        }
        outlet.setHologram(on);
        if (!on) {
            displays.discardHologram(source.getServer(), outlet);
        }
        BankRuntime.save();
        return ok(source, name + (on ? " hologram on" : " hologram off"));
    }

    private static int credit(ServerCommandSource source, String mode, Collection<GameProfile> profiles, String currency, String rawAmount) {
        if (profiles.size() != 1) {
            return fail(source, "Name one player");
        }
        BigInteger amount;
        try {
            amount = Amounts.parse(rawAmount);
        } catch (RuntimeException error) {
            return fail(source, error.getMessage());
        }
        if (books().currency(currency) == null) {
            return fail(source, "Unknown currency");
        }
        GameProfile profile = profiles.iterator().next();
        var online = source.getServer().getPlayerManager().getPlayer(profile.getId());
        if ("check".equals(mode) && online == null) {
            return fail(source, "That player is offline, so the bill can't be given");
        }
        TxResult result = switch (mode) {
            case "unbacked" -> books().creditUnbacked(profile.getId(), currency, amount);
            case "backed" -> books().creditBacked(profile.getId(), currency, amount);
            case "retire" -> books().retire(profile.getId(), currency, amount);
            case "check" -> books().issueCheck(profile.getId(), currency, amount, profile.getName(), null, false);
            default -> TxResult.fail("bad_amount", "Unknown action");
        };
        if (!result.success()) {
            return fail(source, result.message());
        }
        if ("check".equals(mode)) {
            online.getInventory().offerOrDrop(CheckItems.create(books(), books().check(result.instrumentId())));
        }
        BankRuntime.save();
        return ok(source, result.message() + " → " + profile.getName());
    }

    private static int setAlpha(ServerCommandSource source, String currency, String raw) {
        if (books().currency(currency) == null) {
            return fail(source, "Unknown currency");
        }
        BigDecimal alpha;
        try {
            alpha = new BigDecimal(raw);
        } catch (NumberFormatException error) {
            return fail(source, "alpha isn't a number");
        }
        if (alpha.signum() < 0 || alpha.compareTo(new BigDecimal("100000")) > 0) {
            return fail(source, "alpha must be from 0 to 100000 permille");
        }
        books().setAlpha(currency, alpha);
        BankRuntime.save();
        return ok(source, currency + " alpha is now " + alpha.toPlainString() + " permille");
    }

    private static int clamp(ServerCommandSource source, boolean min, String raw) {
        if (raw.equalsIgnoreCase("off")) {
            if (min) {
                books().setClampMin(null);
            } else {
                books().setClampMax(null);
            }
            BankRuntime.save();
            return ok(source, (min ? "Minimum" : "Maximum") + " clamp cleared");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw);
        } catch (NumberFormatException error) {
            return fail(source, "Not a number. Use off to clear the clamp.");
        }
        if (value.signum() <= 0) {
            return fail(source, "Clamp must be positive, or off");
        }
        if (min) {
            books().setClampMin(value);
        } else {
            books().setClampMax(value);
        }
        BankRuntime.save();
        return ok(source, (min ? "Minimum " : "Maximum ") + value.toPlainString());
    }

    private static int setReserve(ServerCommandSource source, String currency, String raw) {
        if (books().currency(currency) == null) {
            return fail(source, "Unknown currency");
        }
        BigInteger amount;
        try {
            amount = "0".equals(raw) ? BigInteger.ZERO : Amounts.parse(raw);
        } catch (RuntimeException error) {
            return fail(source, error.getMessage());
        }
        try {
            books().setReserve(currency, amount);
        } catch (IllegalArgumentException error) {
            return fail(source, error.getMessage());
        }
        BankRuntime.save();
        return ok(source, currency + " reserve is now " + CompactFormat.format(amount));
    }

    private static int supply(ServerCommandSource source) {
        BankBooks books = books();
        source.sendMessage(Text.literal("Status " + books.statusLabelEn() + "  fee "
                + books.feeFraction().movePointRight(2).stripTrailingZeros().toPlainString() + "%").formatted(Formatting.AQUA));
        for (var def : books.currencies()) {
            Supply supply = books.supply(def.id());
            BigDecimal rate = def.id().equals(books.numeraire()) ? null : books.unitsPer(def.id(), books.numeraire());
            String shown = def.nameEn() == null || def.nameEn().isBlank() ? def.id() : def.nameEn();
            source.sendMessage(Text.literal(shown
                    + "  reserve " + CompactFormat.format(supply.reserve())
                    + "  liabilities " + CompactFormat.format(supply.liability())
                    + "  locked " + CompactFormat.format(supply.locked())
                    + "  cover " + CompactFormat.formatRatio(books.tightness(def.id()))
                    + (rate == null ? "" : "  1 " + books.numeraire() + " = " + CompactFormat.formatRate(rate))
            ).formatted(Formatting.GRAY));
        }
        return 1;
    }

    private static int reload(ServerCommandSource source) {
        try {
            var path = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("bank.json");
            if (!java.nio.file.Files.exists(path)) {
                return fail(source, "config/bank.json is missing");
            }
            BankConfig config = ConfigJson.parse(java.nio.file.Files.readString(path));
            BankRuntime.config = config;
            books().applyPolicy(config);
            BankRuntime.save();
            return ok(source, "Reloaded fees, alpha, clamps, and any new currencies. Existing accounts were kept.");
        } catch (Exception error) {
            return fail(source, "Couldn't read the config: " + error.getMessage());
        }
    }

    private static int pref(ServerCommandSource source, Boolean sidebar, Boolean bossbar, Boolean tab) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        books().setPref(source.getPlayerOrThrow().getUuid(), sidebar, bossbar, tab);
        BankRuntime.save();
        return ok(source, "Personal display updated");
    }

    private static int global(ServerCommandSource source, String which, boolean on) {
        BankBooks books = books();
        switch (which) {
            case "sidebar" -> books.setGlobalSidebar(on);
            case "bossbar" -> books.setGlobalBossbar(on);
            case "tab" -> books.setGlobalTab(on);
            case "hologram" -> books.setGlobalHologram(on);
            case "join" -> books.setJoinMessage(on);
            default -> {
                return 0;
            }
        }
        BankRuntime.save();
        return ok(source, which + (on ? " on" : " off"));
    }

    private static BlockPos lookingAt(ServerPlayerEntity player) {
        HitResult hit = player.raycast(6.0, 1.0f, false);
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult block) {
            return block.getBlockPos();
        }
        return player.getBlockPos().down();
    }

    private static boolean validNewOutletName(String name) {
        return name != null && NEW_OUTLET_NAME.matcher(name).matches();
    }

    private static boolean op(ServerCommandSource source) {
        return source.hasPermissionLevel(2);
    }

    private static BankBooks books() {
        return BankRuntime.books();
    }

    private static int ok(ServerCommandSource source, String message) {
        source.sendMessage(Text.literal("[Bank] " + message).formatted(Formatting.GREEN));
        return 1;
    }

    private static int fail(ServerCommandSource source, String message) {
        source.sendError(Text.literal("[Bank] " + message));
        return 0;
    }

    private static int info(ServerCommandSource source, String message) {
        source.sendMessage(Text.literal("[Bank] " + message).formatted(Formatting.GRAY));
        return 0;
    }
}
