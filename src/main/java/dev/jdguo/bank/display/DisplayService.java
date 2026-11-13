package dev.jdguo.bank.display;

import dev.jdguo.bank.currency.CurrencyDef;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.CompactFormat;
import dev.jdguo.bank.outlet.OutletPoint;
import dev.jdguo.bank.persistence.BankRuntime;
import dev.jdguo.bank.text.BankPhrases;
import dev.jdguo.bank.text.BankTalk;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.network.packet.s2c.play.PlayerListHeaderS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardPlayerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.TeamS2CPacket;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lobby readouts built only from vanilla packets and a text display.
 * The sidebar numbers are line positions: 1.20.1 always prints them, and they are not balances.
 */
public final class DisplayService {
    private static final String OBJECTIVE = "banksb";
    private static final Formatting[] TITLE_COLORS = {
            Formatting.AQUA, Formatting.DARK_AQUA, Formatting.BLUE, Formatting.LIGHT_PURPLE, Formatting.GOLD, Formatting.YELLOW
    };

    private final Scoreboard board = new Scoreboard();
    private final ScoreboardObjective objective;
    private final ServerBossBar bossBar;
    private final Map<UUID, Integer> sidebarLines = new HashMap<>();
    private final Set<UUID> sidebarReady = new HashSet<>();
    private int frame;

    public DisplayService() {
        this.objective = board.addObjective(OBJECTIVE, ScoreboardCriterion.DUMMY,
                Text.literal("中央银行"), ScoreboardCriterion.RenderType.INTEGER);
        this.bossBar = new ServerBossBar(Text.literal("中央银行"), BossBar.Color.GREEN, BossBar.Style.NOTCHED_10);
        this.bossBar.setVisible(true);
        for (int i = 0; i < 15; i++) {
            Team team = board.addTeam(teamName(i));
            team.setNameTagVisibilityRule(AbstractTeam.VisibilityRule.NEVER);
            team.setCollisionRule(AbstractTeam.CollisionRule.NEVER);
            team.setColor(Formatting.WHITE);
            board.addPlayerToTeam(entry(i), team);
        }
    }

    public static final String MARKER_TAG = "bankmod_outlet";

    public void tick(MinecraftServer server) {
        syncMarkers(server);
        frame++;
        if (frame % 15 != 0) {
            return;
        }
        BankBooks books = BankRuntime.books();
        refreshBossBar(server, books);
        Text hologram = hologramText(books);
        for (OutletPoint outlet : books.outlets()) {
            syncHologram(server, books, outlet, hologram);
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            refreshPlayer(player, books);
        }
    }

    public void onJoin(ServerPlayerEntity player) {
        resync(player);
        if (BankRuntime.state != null && BankRuntime.books().joinMessage()) {
            player.sendMessage(BankTalk.text(player, BankPhrases.joinHint()), false);
        }
    }

    public void resync(ServerPlayerEntity player) {
        sidebarReady.remove(player.getUuid());
        sidebarLines.remove(player.getUuid());
    }

    public void onQuit(ServerPlayerEntity player) {
        sidebarReady.remove(player.getUuid());
        sidebarLines.remove(player.getUuid());
        bossBar.removePlayer(player);
    }

    public void discardHologram(MinecraftServer server, OutletPoint outlet) {
        Entity entity = findHologram(server, outlet, true);
        if (entity != null) {
            entity.discard();
        }
        outlet.setHologramId(null);
    }

    public void discardMarker(MinecraftServer server, OutletPoint outlet) {
        Entity entity = findMarker(server, outlet, true);
        if (entity != null) {
            entity.discard();
        }
        outlet.setMarkerId(null);
    }

    public void ensureMarker(MinecraftServer server, OutletPoint outlet) {
        pinMarker(server, outlet, true);
    }

    private void syncMarkers(MinecraftServer server) {
        if (BankRuntime.books() == null) {
            return;
        }
        for (OutletPoint outlet : BankRuntime.books().outlets()) {
            pinMarker(server, outlet, false);
        }
    }

    /**
     * A named chest minecart, the same kind of marker the datapack used.
     * Vanilla clients can see it. Right-click is handled as the bank counter.
     */
    private void pinMarker(MinecraftServer server, OutletPoint outlet, boolean loadChunk) {
        ServerWorld world = world(server, outlet.world());
        if (world == null) {
            return;
        }
        if (loadChunk) {
            world.getChunk(outlet.x() >> 4, outlet.z() >> 4);
        } else if (!world.isChunkLoaded(outlet.x() >> 4, outlet.z() >> 4)) {
            return;
        }
        double x = outlet.x() + 0.5;
        double y = outlet.y() + 1.0;
        double z = outlet.z() + 0.5;
        Entity found = outlet.markerId() == null ? null : world.getEntity(outlet.markerId());
        ChestMinecartEntity cart = found instanceof ChestMinecartEntity existing && existing.isAlive()
                && existing.getCommandTags().contains(MARKER_TAG) ? existing : null;
        boolean spawned = false;
        if (cart == null) {
            if (found != null) {
                found.discard();
            }
            cart = new ChestMinecartEntity(EntityType.CHEST_MINECART, world);
            cart.addCommandTag(MARKER_TAG);
            cart.setInvulnerable(true);
            cart.setNoGravity(true);
            cart.setSilent(true);
            cart.setCustomNameVisible(true);
            world.spawnEntity(cart);
            outlet.setMarkerId(cart.getUuid());
            spawned = true;
        }
        cart.setCustomName(Text.literal("中央银行 · " + outlet.name()).formatted(Formatting.AQUA));
        cart.setCustomNameVisible(true);
        cart.setInvulnerable(true);
        cart.setNoGravity(true);
        cart.setVelocity(Vec3d.ZERO);
        cart.refreshPositionAndAngles(x, y, z, cart.getYaw(), 0.0f);
        cart.clear();
        if (spawned) {
            BankRuntime.save();
        }
    }

    private Entity findMarker(MinecraftServer server, OutletPoint outlet, boolean loadChunk) {
        ServerWorld world = world(server, outlet.world());
        if (world == null || outlet.markerId() == null) {
            return null;
        }
        if (loadChunk) {
            world.getChunk(outlet.x() >> 4, outlet.z() >> 4);
        } else if (!world.isChunkLoaded(outlet.x() >> 4, outlet.z() >> 4)) {
            return null;
        }
        return world.getEntity(outlet.markerId());
    }

    private void refreshPlayer(ServerPlayerEntity player, BankBooks books) {
        UUID id = player.getUuid();
        if (books.showSidebar(id)) {
            sendSidebar(player, books);
        } else if (sidebarReady.remove(id)) {
            player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(
                    objective, ScoreboardObjectiveUpdateS2CPacket.REMOVE_MODE));
            sidebarLines.remove(id);
        }
        if (books.showTab(id)) {
            player.networkHandler.sendPacket(new PlayerListHeaderS2CPacket(tabHeader(books), tabFooter()));
        } else {
            player.networkHandler.sendPacket(new PlayerListHeaderS2CPacket(Text.empty(), Text.empty()));
        }
    }

    private void sendSidebar(ServerPlayerEntity player, BankBooks books) {
        List<Text> lines = sidebarLines(player, books);
        objective.setDisplayName(Text.literal("✦ 中央银行 ✦").formatted(TITLE_COLORS[frame % TITLE_COLORS.length], Formatting.BOLD));
        boolean fresh = sidebarReady.add(player.getUuid());
        player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(objective,
                fresh ? ScoreboardObjectiveUpdateS2CPacket.ADD_MODE : ScoreboardObjectiveUpdateS2CPacket.UPDATE_MODE));
        if (fresh) {
            player.networkHandler.sendPacket(new ScoreboardDisplayS2CPacket(Scoreboard.SIDEBAR_DISPLAY_SLOT_ID, objective));
        }
        int previous = sidebarLines.getOrDefault(player.getUuid(), 0);
        for (int i = 0; i < lines.size(); i++) {
            Team team = board.getTeam(teamName(i));
            team.setPrefix(lines.get(i));
            player.networkHandler.sendPacket(TeamS2CPacket.updateTeam(team, true));
            player.networkHandler.sendPacket(new ScoreboardPlayerUpdateS2CPacket(
                    ServerScoreboard.UpdateMode.CHANGE, OBJECTIVE, entry(i), lines.size() - i));
        }
        for (int i = lines.size(); i < previous; i++) {
            player.networkHandler.sendPacket(new ScoreboardPlayerUpdateS2CPacket(
                    ServerScoreboard.UpdateMode.REMOVE, OBJECTIVE, entry(i), 0));
        }
        sidebarLines.put(player.getUuid(), lines.size());
    }

    private List<Text> sidebarLines(ServerPlayerEntity player, BankBooks books) {
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal(frame / 15 % 2 == 0 ? "✦ ✧ ✦ ✧ ✦" : "✧ ✦ ✧ ✦ ✧").formatted(Formatting.LIGHT_PURPLE));
        int shown = 0;
        for (CurrencyDef def : books.currencies()) {
            if (shown++ >= 4) {
                break;
            }
            lines.add(Text.literal(def.name() + " ").formatted(color(def))
                    .append(Text.literal(CompactFormat.format(books.balance(player.getUuid(), def.id()))).formatted(Formatting.WHITE)));
        }
        lines.add(Text.literal("────────").formatted(Formatting.DARK_GRAY));
        CurrencyDef base = books.currency(books.numeraire());
        String baseName = base == null ? books.numeraire() : base.name();
        int rates = 0;
        for (CurrencyDef def : books.currencies()) {
            if (def.id().equals(books.numeraire()) || rates++ >= 3) {
                continue;
            }
            lines.add(Text.literal("1" + baseName + " ").formatted(Formatting.GRAY)
                    .append(Text.literal(CompactFormat.formatRate(books.unitsPer(def.id(), books.numeraire())) + def.name()).formatted(Formatting.WHITE)));
        }
        lines.add(Text.literal("费率 ").formatted(Formatting.GRAY)
                .append(Text.literal(fee(books)).formatted(Formatting.GOLD)));
        CurrencyDef note = books.primaryVirtual();
        if (note != null) {
            lines.add(Text.literal("覆盖 ").formatted(Formatting.GRAY)
                    .append(Text.literal(CompactFormat.formatRatio(books.tightness(note.id()))).formatted(Formatting.WHITE)));
        }
        lines.add(Text.literal(books.statusLabel()).formatted(statusColor(books)));
        lines.add(nearestLine(player, books));
        return lines;
    }

    private Text nearestLine(ServerPlayerEntity player, BankBooks books) {
        String world = player.getWorld().getRegistryKey().getValue().toString();
        OutletPoint best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (OutletPoint outlet : books.outlets()) {
            double distance = outlet.distanceSquared(world, player.getX(), player.getY(), player.getZ());
            if (distance < bestDistance) {
                best = outlet;
                bestDistance = distance;
            }
        }
        if (best == null) {
            return Text.literal("尚未设立网点").formatted(Formatting.DARK_GRAY);
        }
        int blocks = (int) Math.sqrt(bestDistance);
        return Text.literal("网点 ").formatted(Formatting.GRAY)
                .append(Text.literal(best.name() + " " + blocks + "m").formatted(Formatting.AQUA));
    }

    private void refreshBossBar(MinecraftServer server, BankBooks books) {
        CurrencyDef note = books.primaryVirtual();
        BigDecimal tight = note == null ? null : books.tightness(note.id());
        BigDecimal copperPerDiamond = books.currency("copper") == null || books.currency("diamond") == null
                ? null : books.unitsPer("copper", "diamond");
        String rateText = copperPerDiamond == null ? "—" : CompactFormat.formatRate(copperPerDiamond);
        bossBar.setName(Text.literal("汇率：" + rateText + " 铜/钻  ·  " + books.statusLabel())
                .formatted(statusColor(books), Formatting.BOLD));
        float percent = 1.0f;
        if (tight != null) {
            percent = tight.max(BigDecimal.ZERO).min(BigDecimal.ONE).floatValue();
        }
        if (copperPerDiamond == null) {
            percent = 0.0f;
        }
        bossBar.setPercent(percent);
        bossBar.setColor(percent < 0.05f || books.frozen() ? BossBar.Color.RED
                : percent < 0.25f ? BossBar.Color.YELLOW : BossBar.Color.GREEN);
        Set<ServerPlayerEntity> online = new HashSet<>(server.getPlayerManager().getPlayerList());
        for (ServerPlayerEntity watching : List.copyOf(bossBar.getPlayers())) {
            if (!online.contains(watching) || !books.showBossbar(watching.getUuid())) {
                bossBar.removePlayer(watching);
            }
        }
        if (!books.globalBossbar()) {
            return;
        }
        for (ServerPlayerEntity player : online) {
            if (books.showBossbar(player.getUuid()) && !bossBar.getPlayers().contains(player)) {
                bossBar.addPlayer(player);
            }
        }
    }

    private Text hologramText(BankBooks books) {
        MutableText text = Text.empty();
        text.append(Text.literal("✦ 中央银行 ✦\n").formatted(TITLE_COLORS[frame % TITLE_COLORS.length], Formatting.BOLD));
        CurrencyDef base = books.currency(books.numeraire());
        String baseName = base == null ? books.numeraire() : base.name();
        for (CurrencyDef def : books.currencies()) {
            if (def.id().equals(books.numeraire())) {
                continue;
            }
            text.append(Text.literal("1 " + baseName + " = ").formatted(Formatting.GRAY))
                    .append(Text.literal(CompactFormat.formatRate(books.unitsPer(def.id(), books.numeraire()))).formatted(color(def)))
                    .append(Text.literal(" " + def.name() + "\n").formatted(color(def)));
        }
        CurrencyDef note = books.primaryVirtual();
        if (note != null) {
            text.append(Text.literal("兑现覆盖 ").formatted(Formatting.DARK_AQUA))
                    .append(Text.literal(CompactFormat.formatRatio(books.tightness(note.id())) + "\n").formatted(Formatting.WHITE));
            if (note.backing() != null && books.currency(note.backing()) != null) {
                text.append(Text.literal("金库 " + books.currency(note.backing()).name() + " ").formatted(Formatting.GRAY))
                        .append(Text.literal(CompactFormat.format(books.supply(note.backing()).freeReserve()) + "\n").formatted(Formatting.WHITE));
            }
        }
        text.append(Text.literal(books.statusLabel() + "\n").formatted(statusColor(books), Formatting.BOLD));
        text.append(Text.literal("右键此方块开启柜台").formatted(Formatting.DARK_GRAY));
        return text;
    }

    private void syncHologram(MinecraftServer server, BankBooks books, OutletPoint outlet, Text text) {
        if (!books.globalHologram() || !outlet.hologram()) {
            Entity entity = findHologram(server, outlet, false);
            if (entity != null) {
                entity.discard();
                outlet.setHologramId(null);
                BankRuntime.save();
            }
            return;
        }
        ServerWorld world = world(server, outlet.world());
        if (world == null || !world.isChunkLoaded(outlet.x() >> 4, outlet.z() >> 4)) {
            return;
        }
        Entity entity = outlet.hologramId() == null ? null : world.getEntity(outlet.hologramId());
        DisplayEntity.TextDisplayEntity display;
        if (entity instanceof DisplayEntity.TextDisplayEntity existing && existing.isAlive()) {
            display = existing;
            writeHologram(display, text, false);
        } else {
            display = spawn(world, outlet, text);
            outlet.setHologramId(display.getUuid());
            BankRuntime.save();
        }
    }

    private DisplayEntity.TextDisplayEntity spawn(ServerWorld world, OutletPoint outlet, Text text) {
        DisplayEntity.TextDisplayEntity display = new DisplayEntity.TextDisplayEntity(EntityType.TEXT_DISPLAY, world);
        display.refreshPositionAndAngles(outlet.x() + 0.5, outlet.y() + outlet.lift(), outlet.z() + 0.5, 0.0f, 0.0f);
        display.setNoGravity(true);
        display.setInvulnerable(true);
        display.setSilent(true);
        display.addCommandTag("bankmod_holo");
        writeHologram(display, text, true);
        world.spawnEntity(display);
        return display;
    }

    /**
     * 1.20.1 keeps the text-display setters private. The vanilla NBT format is the supported way
     * for a server mod to style a display without a mixin.
     */
    private static void writeHologram(DisplayEntity.TextDisplayEntity display, Text text, boolean withScale) {
        net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
        display.writeNbt(nbt);
        nbt.putString("id", "minecraft:text_display");
        nbt.putString("text", Text.Serializer.toJson(text));
        nbt.putString("billboard", "center");
        nbt.putString("alignment", "center");
        nbt.putInt("line_width", 220);
        nbt.putInt("background", 0x99001018);
        nbt.putByte("text_opacity", (byte) -1);
        nbt.putBoolean("shadow", true);
        nbt.putBoolean("see_through", false);
        nbt.putBoolean("default_background", false);
        nbt.putFloat("view_range", 2.0f);
        nbt.putFloat("shadow_radius", 0.0f);
        if (withScale) {
            net.minecraft.nbt.NbtCompound transformation = new net.minecraft.nbt.NbtCompound();
            transformation.put("translation", vec(0, 0, 0));
            transformation.put("scale", vec(1.2, 1.2, 1.2));
            transformation.put("left_rotation", vec(0, 0, 0, 1));
            transformation.put("right_rotation", vec(0, 0, 0, 1));
            nbt.put("transformation", transformation);
        }
        display.readNbt(nbt);
    }

    private static net.minecraft.nbt.NbtList vec(double... values) {
        net.minecraft.nbt.NbtList list = new net.minecraft.nbt.NbtList();
        for (double value : values) {
            list.add(net.minecraft.nbt.NbtFloat.of((float) value));
        }
        return list;
    }

    private Entity findHologram(MinecraftServer server, OutletPoint outlet, boolean loadChunk) {
        ServerWorld world = world(server, outlet.world());
        if (world == null || outlet.hologramId() == null) {
            return null;
        }
        if (loadChunk) {
            world.getChunk(outlet.x() >> 4, outlet.z() >> 4);
        } else if (!world.isChunkLoaded(outlet.x() >> 4, outlet.z() >> 4)) {
            return null;
        }
        return world.getEntity(outlet.hologramId());
    }

    private static Text tabHeader(BankBooks books) {
        MutableText header = Text.literal("✦ 中央银行 ✦\n").formatted(Formatting.AQUA, Formatting.BOLD);
        CurrencyDef base = books.currency(books.numeraire());
        String baseName = base == null ? books.numeraire() : base.name();
        boolean first = true;
        for (CurrencyDef def : books.currencies()) {
            if (def.id().equals(books.numeraire())) {
                continue;
            }
            if (!first) {
                header.append(Text.literal("   ").formatted(Formatting.DARK_GRAY));
            }
            first = false;
            header.append(Text.literal("1" + baseName + "=").formatted(Formatting.GRAY))
                    .append(Text.literal(CompactFormat.formatRate(books.unitsPer(def.id(), books.numeraire())) + def.name()).formatted(color(def)));
        }
        header.append(Text.literal("\n费率 " + fee(books) + "  ·  " + books.statusLabel()).formatted(statusColor(books)));
        return header;
    }

    private static Text tabFooter() {
        return Text.literal("右键银行网点打开柜台  ·  附近 /bank").formatted(Formatting.DARK_GRAY);
    }

    private static String fee(BankBooks books) {
        return books.feeFraction().multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
    }

    private static Formatting statusColor(BankBooks books) {
        String label = books.statusLabel();
        if (label.contains("金圆券") || label.contains("停止")) {
            return Formatting.RED;
        }
        if (label.contains("告急")) {
            return Formatting.GOLD;
        }
        if (label.contains("偏紧")) {
            return Formatting.YELLOW;
        }
        return Formatting.GREEN;
    }

    private static Formatting color(CurrencyDef def) {
        if (def == null) {
            return Formatting.WHITE;
        }
        Formatting formatting = Formatting.byName(def.color());
        return formatting == null ? Formatting.WHITE : formatting;
    }

    private static ServerWorld world(MinecraftServer server, String id) {
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(id)) {
                return world;
            }
        }
        return null;
    }

    private static String entry(int line) {
        return "\u00a7" + Integer.toHexString(line) + "\u00a7r";
    }

    private static String teamName(int line) {
        return "bk" + line;
    }
}
