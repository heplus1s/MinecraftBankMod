package dev.jdguo.bank.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.jdguo.bank.currency.BankConfig;
import dev.jdguo.bank.currency.ConfigJson;
import dev.jdguo.bank.display.DisplayService;
import dev.jdguo.bank.gui.BankMenus;
import dev.jdguo.bank.currency.CurrencyDef;
import dev.jdguo.bank.currency.CurrencyKind;
import dev.jdguo.bank.instruments.CheckItems;
import dev.jdguo.bank.instruments.NotePaper;
import dev.jdguo.bank.instruments.PaperTake;
import dev.jdguo.bank.instruments.PresentedNote;
import dev.jdguo.bank.ledger.Amounts;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.TxResult;
import dev.jdguo.bank.market.NoteTrade;
import dev.jdguo.bank.market.PersonalNote;
import dev.jdguo.bank.market.Redemption;
import dev.jdguo.bank.market.SwapTrade;
import dev.jdguo.bank.outlet.OutletPoint;
import dev.jdguo.bank.persistence.BankRuntime;
import dev.jdguo.bank.text.BankPhrases;
import dev.jdguo.bank.text.BankTalk;
import dev.jdguo.bank.text.Spoken;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.UUID;
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
                            return ok(src, BankPhrases.freeze(on));
                        }).requires(BankCommands::op))
                        .then(literal("fee").requires(BankCommands::op).then(argument("percent", DoubleArgumentType.doubleArg(0, 100)).executes(ctx -> {
                            books().setFeeFraction(BigDecimal.valueOf(DoubleArgumentType.getDouble(ctx, "percent")).movePointLeft(2));
                            BankRuntime.save();
                            return ok(ctx.getSource(), BankPhrases.feeSet(DoubleArgumentType.getDouble(ctx, "percent")));
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
                        .then(literal("personal").requires(BankCommands::op)
                                .then(literal("get").then(argument("player", GameProfileArgumentType.gameProfile()).executes(ctx -> personalGet(ctx.getSource(), GameProfileArgumentType.getProfileArgument(ctx, "player")))))
                                .then(literal("set").then(argument("player", GameProfileArgumentType.gameProfile())
                                        .then(argument("currency", StringArgumentType.word())
                                                .then(argument("amount", StringArgumentType.word()).executes(ctx -> personalSet(
                                                        ctx.getSource(),
                                                        GameProfileArgumentType.getProfileArgument(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "currency"),
                                                        StringArgumentType.getString(ctx, "amount"))))))))
                        .then(literal("withdraw")
                                .then(argument("currency", StringArgumentType.word())
                                        .then(argument("amount", StringArgumentType.word()).executes(ctx -> withdraw(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "currency"),
                                                StringArgumentType.getString(ctx, "amount"))))))
                        .then(literal("deposit").executes(ctx -> depositHeld(ctx.getSource())))
                        .then(literal("note")
                                .then(literal("create")
                                        .then(literal("diamond").then(argument("name", StringArgumentType.string())
                                                .then(argument("par", StringArgumentType.word()).executes(ctx -> createNote(ctx.getSource(), false, StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "par"))))))
                                        .then(literal("copper").then(argument("name", StringArgumentType.string())
                                                .then(argument("par", StringArgumentType.word()).executes(ctx -> createNote(ctx.getSource(), true, StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "par")))))))
                                .then(literal("issue").then(argument("amount", StringArgumentType.word()).executes(ctx -> issueNote(ctx.getSource(), StringArgumentType.getString(ctx, "amount")))))
                                .then(literal("withdraw")
                                        .then(argument("amount", StringArgumentType.word()).executes(ctx -> withdrawPersonal(ctx.getSource(), null, StringArgumentType.getString(ctx, "amount"))))
                                        .then(argument("name", StringArgumentType.string())
                                                .then(argument("amount", StringArgumentType.word()).executes(ctx -> withdrawPersonal(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        StringArgumentType.getString(ctx, "amount"))))))
                                .then(literal("rate").executes(ctx -> noteRate(ctx.getSource(), null))
                                        .then(argument("name", StringArgumentType.string()).executes(ctx -> noteRate(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                                .then(literal("buy").then(argument("name", StringArgumentType.string())
                                        .then(argument("amount", StringArgumentType.word()).executes(ctx -> tradeNote(ctx.getSource(), true, StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "amount"))))))
                                .then(literal("sell").then(argument("name", StringArgumentType.string())
                                        .then(argument("amount", StringArgumentType.word()).executes(ctx -> tradeNote(ctx.getSource(), false, StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "amount"))))))
                                .then(literal("swap")
                                        .then(argument("pay", StringArgumentType.string())
                                                .then(argument("receive", StringArgumentType.string())
                                                        .then(argument("amount", StringArgumentType.word()).executes(ctx -> swapNote(
                                                                ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "pay"),
                                                                StringArgumentType.getString(ctx, "receive"),
                                                                StringArgumentType.getString(ctx, "amount")))))))
                                .then(literal("redeem")
                                        .then(literal("paper").then(argument("currency", StringArgumentType.word()).executes(ctx -> redeemPaper(
                                                ctx.getSource(), StringArgumentType.getString(ctx, "currency")))))
                                        .then(argument("name", StringArgumentType.string())
                                                .then(argument("currency", StringArgumentType.word())
                                                        .then(argument("amount", StringArgumentType.word()).executes(ctx -> redeemNote(
                                                                ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                StringArgumentType.getString(ctx, "currency"),
                                                                StringArgumentType.getString(ctx, "amount")))))))
                                .then(literal("coverage").requires(BankCommands::op).then(argument("ratio", StringArgumentType.word()).executes(ctx -> noteCoverage(ctx.getSource(), StringArgumentType.getString(ctx, "ratio"))))))
                        .then(literal("supply").requires(BankCommands::op).executes(ctx -> supply(ctx.getSource())))
                        .then(literal("audit").requires(BankCommands::op).executes(ctx -> {
                            ctx.getSource().sendMessage(Text.literal(books().audit()).formatted(Formatting.GRAY));
                            return 1;
                        }))
                        .then(literal("reload").requires(BankCommands::op).executes(ctx -> reload(ctx.getSource())))
                        .then(literal("import-scores").requires(BankCommands::op).executes(ctx -> {
                            int count = ScoreImporter.importAll(ctx.getSource().getServer(), books());
                            BankRuntime.save();
                            return ok(ctx.getSource(), BankPhrases.imported(count));
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
            info(source, books().outlets().isEmpty() ? BankPhrases.noOutletsYet() : BankPhrases.notInRange());
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
            return info(source, BankPhrases.noOutlets());
        }
        for (OutletPoint outlet : books().outlets()) {
            source.sendMessage(BankTalk.text(source, BankPhrases.outletLine(
                    outlet.name(), outlet.world(), outlet.x(), outlet.y(), outlet.z(), outlet.radius())));
        }
        return books().outlets().size();
    }

    private static int setOutlet(ServerCommandSource source, DisplayService displays, String name, boolean mustExist) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!mustExist && !validNewOutletName(name)) {
            return fail(source, BankPhrases.badOutletName());
        }
        ServerPlayerEntity player = source.getPlayerOrThrow();
        OutletPoint existing = books().outlet(name);
        if (mustExist && existing == null) {
            return fail(source, BankPhrases.noSuchOutlet());
        }
        if (!mustExist && existing != null) {
            return fail(source, BankPhrases.outletExists());
        }
        BlockPos pos = lookingAt(player);
        String world = player.getWorld().getRegistryKey().getValue().toString();
        if (existing == null) {
            books().addOutlet(new OutletPoint(name, world, pos.getX(), pos.getY(), pos.getZ(),
                    BankRuntime.config.outletRadius(), 2.4, true, null));
        } else {
            displays.discardHologram(source.getServer(), existing);
            displays.discardMarker(source.getServer(), existing);
            existing.relocate(world, pos.getX(), pos.getY(), pos.getZ());
        }
        OutletPoint placed = existing == null ? books().outlet(name) : existing;
        if (placed != null) {
            displays.ensureMarker(source.getServer(), placed);
        }
        BankRuntime.save();
        return ok(source, BankPhrases.outletSet(name, pos.getX(), pos.getY(), pos.getZ()));
    }

    private static int removeOutlet(ServerCommandSource source, DisplayService displays, String name) {
        OutletPoint outlet = books().outlet(name);
        if (outlet == null) {
            return fail(source, BankPhrases.noSuchOutlet());
        }
        displays.discardHologram(source.getServer(), outlet);
        displays.discardMarker(source.getServer(), outlet);
        books().removeOutlet(name);
        BankRuntime.save();
        return ok(source, BankPhrases.outletRemoved(name));
    }

    private static int teleport(ServerCommandSource source, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        OutletPoint outlet = books().outlet(name);
        if (outlet == null) {
            return fail(source, BankPhrases.noSuchOutlet());
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
            return fail(source, BankPhrases.dimensionUnloaded());
        }
        player.teleport(target, outlet.x() + 0.5, outlet.y() + 1.0, outlet.z() + 0.5, player.getYaw(), player.getPitch());
        return ok(source, BankPhrases.teleported(name));
    }

    private static int radius(ServerCommandSource source, String name, double blocks) {
        OutletPoint outlet = books().outlet(name);
        if (outlet == null) {
            return fail(source, BankPhrases.noSuchOutlet());
        }
        outlet.setRadius(blocks);
        BankRuntime.save();
        return ok(source, BankPhrases.radiusSet(name, blocks));
    }

    private static int lift(ServerCommandSource source, DisplayService displays, String name, double blocks) {
        OutletPoint outlet = books().outlet(name);
        if (outlet == null) {
            return fail(source, BankPhrases.noSuchOutlet());
        }
        displays.discardHologram(source.getServer(), outlet);
        outlet.setLift(blocks);
        BankRuntime.save();
        return ok(source, BankPhrases.liftSet(name, blocks));
    }

    private static int hologram(ServerCommandSource source, DisplayService displays, String name, boolean on) {
        OutletPoint outlet = books().outlet(name);
        if (outlet == null) {
            return fail(source, BankPhrases.noSuchOutlet());
        }
        outlet.setHologram(on);
        if (!on) {
            displays.discardHologram(source.getServer(), outlet);
        }
        BankRuntime.save();
        return ok(source, BankPhrases.hologramSet(name, on));
    }

    private static int credit(ServerCommandSource source, String mode, Collection<GameProfile> profiles, String currency, String rawAmount) {
        if (profiles.size() != 1) {
            return fail(source, BankPhrases.nameOnePlayer());
        }
        BigInteger amount;
        try {
            amount = Amounts.parse(rawAmount);
        } catch (RuntimeException error) {
            return fail(source, BankPhrases.amountProblem(error.getMessage()));
        }
        if (books().currency(currency) == null) {
            return fail(source, BankPhrases.failure(BankPhrases.Kind.GENERIC, "unknown_currency", currency, null));
        }
        GameProfile profile = profiles.iterator().next();
        var online = source.getServer().getPlayerManager().getPlayer(profile.getId());
        if ("check".equals(mode) && online == null) {
            return fail(source, BankPhrases.playerOffline());
        }
        TxResult result = switch (mode) {
            case "unbacked" -> books().creditUnbacked(profile.getId(), currency, amount);
            case "backed" -> books().creditBacked(profile.getId(), currency, amount);
            case "retire" -> books().retire(profile.getId(), currency, amount);
            case "check" -> books().issueCheck(profile.getId(), currency, amount, profile.getName(), null, false);
            default -> TxResult.fail("bad_amount", "Unknown action");
        };
        if (!result.success()) {
            return fail(source, BankPhrases.failure(BankPhrases.Kind.GENERIC, result.code(), currency, null));
        }
        if ("check".equals(mode)) {
            online.getInventory().offerOrDrop(CheckItems.create(books(), books().check(result.instrumentId())));
        }
        BankRuntime.save();
        Spoken done = "check".equals(mode)
                ? BankPhrases.issue(books(), currency, result.amount(), profile.getName())
                : BankPhrases.credit(books(), mode, currency, result.amount(), profile.getName());
        return ok(source, done);
    }

    private static int setAlpha(ServerCommandSource source, String currency, String raw) {
        if (books().currency(currency) == null) {
            return fail(source, BankPhrases.failure(BankPhrases.Kind.GENERIC, "unknown_currency", currency, null));
        }
        BigDecimal alpha;
        try {
            alpha = new BigDecimal(raw);
        } catch (NumberFormatException error) {
            return fail(source, BankPhrases.alphaNotNumber());
        }
        if (alpha.signum() < 0 || alpha.compareTo(new BigDecimal("100000")) > 0) {
            return fail(source, BankPhrases.alphaRange());
        }
        books().setAlpha(currency, alpha);
        BankRuntime.save();
        return ok(source, BankPhrases.alphaSet(currency, alpha.toPlainString()));
    }

    private static int clamp(ServerCommandSource source, boolean min, String raw) {
        if (raw.equalsIgnoreCase("off")) {
            if (min) {
                books().setClampMin(null);
            } else {
                books().setClampMax(null);
            }
            BankRuntime.save();
            return ok(source, BankPhrases.clampCleared(min));
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw);
        } catch (NumberFormatException error) {
            return fail(source, BankPhrases.clampNotNumber());
        }
        if (value.signum() <= 0) {
            return fail(source, BankPhrases.clampPositive());
        }
        if (min) {
            books().setClampMin(value);
        } else {
            books().setClampMax(value);
        }
        BankRuntime.save();
        return ok(source, BankPhrases.clampSet(min, value.toPlainString()));
    }

    private static int setReserve(ServerCommandSource source, String currency, String raw) {
        if (books().currency(currency) == null) {
            return fail(source, BankPhrases.failure(BankPhrases.Kind.GENERIC, "unknown_currency", currency, null));
        }
        BigInteger amount;
        try {
            amount = "0".equals(raw) ? BigInteger.ZERO : Amounts.parse(raw);
        } catch (RuntimeException error) {
            return fail(source, BankPhrases.amountProblem(error.getMessage()));
        }
        try {
            books().setOpeningReserve(currency, amount);
        } catch (IllegalArgumentException error) {
            return fail(source, BankPhrases.reserveRejected(error.getMessage()));
        }
        BankRuntime.save();
        return ok(source, BankPhrases.reserveSet(books(), currency));
    }

    private static int personalGet(ServerCommandSource source, Collection<GameProfile> profiles) {
        if (profiles.size() != 1) {
            return fail(source, BankPhrases.nameOnePlayer());
        }
        GameProfile profile = profiles.iterator().next();
        return ok(source, BankPhrases.personalGet(profile.getName(), books(), profile.getId()));
    }

    private static int personalSet(ServerCommandSource source, Collection<GameProfile> profiles, String currency, String raw) {
        if (profiles.size() != 1) {
            return fail(source, BankPhrases.nameOnePlayer());
        }
        if (books().currency(currency) == null) {
            return fail(source, BankPhrases.failure(BankPhrases.Kind.GENERIC, "unknown_currency", currency, null));
        }
        BigInteger amount;
        try {
            amount = "0".equals(raw) ? BigInteger.ZERO : Amounts.parse(raw);
        } catch (RuntimeException error) {
            return fail(source, BankPhrases.amountProblem(error.getMessage()));
        }
        GameProfile profile = profiles.iterator().next();
        try {
            books().setPersonalReserve(profile.getId(), currency, amount);
        } catch (IllegalArgumentException error) {
            return fail(source, BankPhrases.reserveRejected(error.getMessage()));
        }
        BankRuntime.save();
        return ok(source, BankPhrases.personalSet(profile.getName(), books(), currency, amount));
    }

    private static int createNote(ServerCommandSource source, boolean copperPar, String name, String rawPar) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BigInteger par;
        try {
            par = Amounts.parse(rawPar);
        } catch (RuntimeException error) {
            return fail(source, BankPhrases.amountProblem(error.getMessage()));
        }
        NoteTrade result = books().createPersonalNote(source.getPlayerOrThrow().getUuid(), name, copperPar, par);
        if (!result.success()) {
            return fail(source, BankPhrases.noteRejected(result.code()));
        }
        BankRuntime.save();
        return ok(source, BankPhrases.noteNamed(result.note()));
    }

    private static int withdraw(ServerCommandSource source, String currency, String raw) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BigInteger amount;
        try {
            amount = Amounts.parse(raw);
        } catch (RuntimeException error) {
            return fail(source, BankPhrases.amountProblem(error.getMessage()));
        }
        ServerPlayerEntity player = source.getPlayerOrThrow();
        CurrencyDef def = books().currency(currency);
        if (def == null) {
            return fail(source, BankPhrases.failure(BankPhrases.Kind.WITHDRAW, "unknown_currency", currency, null));
        }
        if (def.kind() == CurrencyKind.VIRTUAL) {
            CurrencyDef central = books().primaryVirtual();
            if (central == null || !central.id().equals(def.id())) {
                return fail(source, BankPhrases.failure(BankPhrases.Kind.WITHDRAW, "not_commodity", currency, null));
            }
            PaperTake take = books().issueCentralPaper(player.getUuid(), amount);
            if (!take.result().success()) {
                return fail(source, BankPhrases.failure(BankPhrases.Kind.WITHDRAW, take.result().code(), currency, null));
            }
            NotePaper.give(player, new PresentedNote(def.id(), null, take.face()), def.name(), take.sheets());
            BankRuntime.save();
            return ok(source, BankPhrases.withdraw(books(), player.getUuid(), def.id(), take.result().amount()));
        }
        TxResult result = books().withdraw(player.getUuid(), currency, amount, false);
        if (!result.success()) {
            return fail(source, BankPhrases.failure(BankPhrases.Kind.WITHDRAW, result.code(), currency, null));
        }
        giveCommodity(player, def, result.amount());
        if (result.copperPaid().signum() > 0) {
            giveCommodity(player, books().currency("copper"), result.copperPaid());
        }
        BankRuntime.save();
        return ok(source, BankPhrases.withdraw(books(), player.getUuid(), currency, result.amount(), result.copperPaid()));
    }

    private static int depositHeld(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        ItemStack hand = player.getMainHandStack();
        if (!NotePaper.tagged(hand)) {
            return fail(source, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, "not_note", null, null));
        }
        PresentedNote presented = NotePaper.read(hand);
        if (presented == null) {
            player.getInventory().setStack(player.getInventory().selectedSlot, ItemStack.EMPTY);
            return fail(source, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, "note_tampered", null, null));
        }
        TxResult result = books().redeemPaper(player.getUuid(), presented, BigInteger.valueOf(hand.getCount()));
        if (result.success() || "note_spent".equals(result.code()) || "note_tampered".equals(result.code())) {
            player.getInventory().setStack(player.getInventory().selectedSlot, ItemStack.EMPTY);
        }
        if (!result.success()) {
            return fail(source, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, result.code(), null, null));
        }
        BankRuntime.save();
        String zh;
        String en;
        if (presented.central()) {
            CurrencyDef def = books().currency(presented.currency());
            zh = def == null ? presented.currency() : def.name();
            en = "bank note";
        } else {
            PersonalNote personal = books().personalNoteById(presented.personalNote());
            String name = personal == null ? "?" : personal.name();
            zh = "个人银行券「" + name + "」";
            en = "personal note \"" + name + "\"";
        }
        return ok(source, BankPhrases.depositPaper(books(), player.getUuid(), zh, en, result.amount(), "trimmed".equals(result.code())));
    }

    private static int withdrawPersonal(ServerCommandSource source, String name, String raw) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BigInteger amount;
        try {
            amount = Amounts.parse(raw);
        } catch (RuntimeException error) {
            return fail(source, BankPhrases.amountProblem(error.getMessage()));
        }
        ServerPlayerEntity player = source.getPlayerOrThrow();
        PersonalNote note = name == null ? books().personalNote(player.getUuid()) : books().personalNoteByName(name);
        if (note == null) {
            return fail(source, BankPhrases.noteRejected("none"));
        }
        PaperTake take = books().issuePersonalPaper(player.getUuid(), note.id(), amount);
        if (!take.result().success()) {
            return fail(source, "none".equals(take.result().code())
                    ? BankPhrases.noteRejected("none")
                    : BankPhrases.failure(BankPhrases.Kind.WITHDRAW, take.result().code(), null, null));
        }
        NotePaper.give(player, new PresentedNote(null, note.id(), take.face()), note.name(), take.sheets());
        BankRuntime.save();
        return ok(source, BankPhrases.withdrawPersonal(
                books(), player.getUuid(), note.name(), take.result().amount(), books().personalHolding(note.id(), player.getUuid())));
    }

    private static void giveCommodity(ServerPlayerEntity player, CurrencyDef def, BigInteger amount) {
        if (def == null || amount == null || amount.signum() <= 0) {
            return;
        }
        var item = Registries.ITEM.get(new Identifier(def.itemId()));
        if (item == Items.AIR) {
            return;
        }
        int left = amount.intValue();
        while (left > 0) {
            int count = Math.min(item.getMaxCount(), left);
            player.getInventory().offerOrDrop(new ItemStack(item, count));
            left -= count;
        }
    }

    private static int issueNote(ServerCommandSource source, String raw) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BigInteger amount;
        try {
            amount = Amounts.parse(raw);
        } catch (RuntimeException error) {
            return fail(source, BankPhrases.amountProblem(error.getMessage()));
        }
        UUID player = source.getPlayerOrThrow().getUuid();
        NoteTrade result = books().issuePersonalNote(player, amount);
        if (!result.success()) {
            return fail(source, BankPhrases.noteRejected(result.code()));
        }
        BankRuntime.save();
        return ok(source, BankPhrases.noteIssued(books(), result.note(), amount));
    }

    private static int swapNote(ServerCommandSource source, String pay, String receive, String raw) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BigInteger amount;
        try {
            amount = Amounts.parse(raw);
        } catch (RuntimeException error) {
            return fail(source, BankPhrases.amountProblem(error.getMessage()));
        }
        UUID player = source.getPlayerOrThrow().getUuid();
        SwapTrade result = books().swapPersonalNotes(player, pay, receive, amount);
        if (!result.success()) {
            return fail(source, BankPhrases.noteRejected(result.code()));
        }
        BankRuntime.save();
        return ok(source, BankPhrases.noteSwap(result, books(), player));
    }

    private static int redeemNote(ServerCommandSource source, String name, String currency, String raw) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BigInteger amount;
        try {
            amount = Amounts.parse(raw);
        } catch (RuntimeException error) {
            return fail(source, BankPhrases.amountProblem(error.getMessage()));
        }
        UUID player = source.getPlayerOrThrow().getUuid();
        Redemption result = books().redeemPersonal(player, name, amount, currency);
        if (!result.success()) {
            return fail(source, BankPhrases.noteRejected(result.code()));
        }
        BankRuntime.save();
        return ok(source, BankPhrases.noteRedeem(result, books(), player));
    }

    private static int redeemPaper(ServerCommandSource source, String currency) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        ItemStack hand = player.getMainHandStack();
        if (!NotePaper.tagged(hand)) {
            return fail(source, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, "not_note", null, null));
        }
        PresentedNote presented = NotePaper.read(hand);
        if (presented == null) {
            player.getInventory().setStack(player.getInventory().selectedSlot, ItemStack.EMPTY);
            return fail(source, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, "note_tampered", null, null));
        }
        if (presented.central()) {
            return fail(source, BankPhrases.failure(BankPhrases.Kind.DEPOSIT, "not_note", null, null));
        }
        Redemption result = books().redeemPersonalPaper(
                player.getUuid(), presented.personalNote(), presented.face(), BigInteger.valueOf(hand.getCount()), currency);
        if (result.success() || "note_spent".equals(result.code()) || "note_tampered".equals(result.code())) {
            player.getInventory().setStack(player.getInventory().selectedSlot, ItemStack.EMPTY);
        }
        if (!result.success()) {
            return fail(source, "note_spent".equals(result.code()) || "note_tampered".equals(result.code())
                    ? BankPhrases.failure(BankPhrases.Kind.DEPOSIT, result.code(), null, null)
                    : BankPhrases.noteRejected(result.code()));
        }
        BankRuntime.save();
        return ok(source, BankPhrases.noteRedeem(result, books(), player.getUuid()));
    }

    private static int noteRate(ServerCommandSource source, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        PersonalNote note = name == null ? books().personalNote(source.getPlayerOrThrow().getUuid()) : books().personalNoteByName(name);
        if (note == null) {
            return fail(source, BankPhrases.noteRejected("none"));
        }
        return info(source, BankPhrases.noteRate(books(), note));
    }

    private static int tradeNote(ServerCommandSource source, boolean buy, String name, String raw) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BigInteger amount;
        try {
            amount = Amounts.parse(raw);
        } catch (RuntimeException error) {
            return fail(source, BankPhrases.amountProblem(error.getMessage()));
        }
        UUID player = source.getPlayerOrThrow().getUuid();
        NoteTrade result = buy ? books().buyPersonalNote(player, name, amount) : books().sellPersonalNote(player, name, amount);
        if (!result.success()) {
            return fail(source, BankPhrases.noteRejected(result.code()));
        }
        BankRuntime.save();
        return ok(source, BankPhrases.noteTrade(buy, result.note().name(), amount, result.centralPaid(), result.copperPaid(), books(), player));
    }

    private static int noteCoverage(ServerCommandSource source, String raw) {
        try {
            books().setPersonalNoteCoverage(new BigDecimal(raw));
        } catch (RuntimeException error) {
            return fail(source, BankPhrases.noteRejected("coverage"));
        }
        BankRuntime.save();
        return ok(source, BankPhrases.noteCoverage(books().personalNoteCoverage()));
    }

    private static int supply(ServerCommandSource source) {
        for (Spoken line : BankPhrases.supply(books())) {
            source.sendMessage(BankTalk.text(source, line));
        }
        return 1;
    }

    private static int reload(ServerCommandSource source) {
        try {
            var path = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("bank.json");
            if (!java.nio.file.Files.exists(path)) {
                return fail(source, BankPhrases.missingConfig());
            }
            BankConfig config = ConfigJson.parse(java.nio.file.Files.readString(path));
            BankRuntime.config = config;
            books().applyPolicy(config);
            BankRuntime.save();
            return ok(source, BankPhrases.reloaded());
        } catch (Exception error) {
            return fail(source, BankPhrases.configUnreadable(String.valueOf(error.getMessage())));
        }
    }

    private static int pref(ServerCommandSource source, Boolean sidebar, Boolean bossbar, Boolean tab) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        books().setPref(source.getPlayerOrThrow().getUuid(), sidebar, bossbar, tab);
        BankRuntime.save();
        return ok(source, BankPhrases.displayUpdated());
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
        return ok(source, BankPhrases.globalToggle(which, on));
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

    private static int ok(ServerCommandSource source, Spoken spoken) {
        source.sendMessage(BankTalk.text(source, spoken));
        return 1;
    }

    private static int fail(ServerCommandSource source, Spoken spoken) {
        source.sendError(BankTalk.text(source, spoken));
        return 0;
    }

    private static int info(ServerCommandSource source, Spoken spoken) {
        source.sendMessage(BankTalk.text(source, spoken));
        return 0;
    }
}
