package dev.jdguo.bank.display;

import dev.jdguo.bank.currency.CurrencyDef;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.ledger.CompactFormat;
import dev.jdguo.bank.outlet.OutletPoint;
import dev.jdguo.bank.persistence.BankRuntime;
import dev.jdguo.bank.text.BankPhrases;
import dev.jdguo.bank.text.BankTalk;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.decoration.InteractionEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.network.packet.s2c.play.PlayerListHeaderS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
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
 * The scoreboard sidebar is not used. Saved sidebar preferences are left on disk and never sent.
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
    public static final String HOLO_TAG = "bankmod_holo";

    public void tick(MinecraftServer server) {
        syncMarkers(server);
        if (frame % 20 == 0) {
            sweepOrphanDisplays(server);
        }
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
        dropEntity(entity);
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
     * Removes bank counters and holograms whose ids are no longer in the outlet list.
     * Deleting the bank save leaves those vanilla displays in the world; the next tick
     * of a loaded chunk drops only entities carrying the bank outlet tags.
     */
    private void sweepOrphanDisplays(MinecraftServer server) {
        if (BankRuntime.books() == null) {
            return;
        }
        Set<UUID> kept = new HashSet<>();
        for (OutletPoint outlet : BankRuntime.books().outlets()) {
            if (outlet.markerId() != null) {
                kept.add(outlet.markerId());
            }
            if (outlet.hologramId() != null) {
                kept.add(outlet.hologramId());
            }
        }
        for (ServerWorld world : server.getWorlds()) {
            List<Entity> doomed = new ArrayList<>();
            for (Entity entity : world.iterateEntities()) {
                UUID vehicle = entity.getVehicle() == null ? null : entity.getVehicle().getUuid();
                if (OutletDisplaySweep.shouldDiscard(entity.getCommandTags(), entity.getUuid(), vehicle, kept)) {
                    doomed.add(entity);
                }
            }
            doomed.sort((left, right) -> Boolean.compare(left.getVehicle() != null, right.getVehicle() != null));
            for (Entity entity : doomed) {
                if (!entity.isRemoved()) {
                    dropEntity(entity);
                }
            }
        }
    }

    /**
     * A vanilla block-display counter. Clients see oak, quartz, a bookshelf, and a lectern
     * with no resource pack. The invisible interaction is what a right-click hits.
     * An older chest-minecart marker is replaced the next time its chunk is loaded.
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
        InteractionEntity counter = found instanceof InteractionEntity existing && existing.isAlive()
                && existing.getCommandTags().contains(MARKER_TAG) ? existing : null;
        boolean spawned = false;
        if (counter == null) {
            dropEntity(found);
            counter = new InteractionEntity(EntityType.INTERACTION, world);
            counter.addCommandTag(MARKER_TAG);
            counter.setInvulnerable(true);
            counter.setSilent(true);
            counter.setCustomNameVisible(true);
            world.spawnEntity(counter);
            NbtCompound size = new NbtCompound();
            counter.writeNbt(size);
            size.putFloat("width", 1.7f);
            size.putFloat("height", 1.35f);
            size.putBoolean("response", true);
            counter.readNbt(size);
            outlet.setMarkerId(counter.getUuid());
            spawned = true;
        }
        counter.setCustomName(Text.literal("中央银行 · " + outlet.name()).formatted(Formatting.AQUA));
        counter.setCustomNameVisible(true);
        counter.setInvulnerable(true);
        counter.setVelocity(Vec3d.ZERO);
        counter.refreshPositionAndAngles(x, y, z, counter.getYaw(), 0.0f);
        if (counter.getPassengerList().isEmpty()) {
            attachCounter(counter);
        }
        if (spawned) {
            BankRuntime.save();
        }
    }

    /** Desk, pale top, brass edge, bookshelf back, and a lectern. Offsets are from the counter's feet. */
    private void attachCounter(InteractionEntity counter) {
        counterPart(counter, Blocks.DARK_OAK_PLANKS, -0.75f, 0.0f, -0.38f, 1.5f, 0.92f, 0.76f);
        counterPart(counter, Blocks.SMOOTH_QUARTZ, -0.80f, 0.92f, -0.42f, 1.6f, 0.10f, 0.84f);
        counterPart(counter, Blocks.GOLD_BLOCK, -0.80f, 1.00f, -0.46f, 1.6f, 0.04f, 0.05f);
        counterPart(counter, Blocks.BOOKSHELF, -0.60f, 0.0f, 0.28f, 1.2f, 1.55f, 0.28f);
        counterPart(counter, Blocks.LECTERN, -0.28f, 1.02f, -0.16f, 0.55f, 0.55f, 0.55f);
    }

    private void counterPart(InteractionEntity counter, Block block, float tx, float ty, float tz, float sx, float sy, float sz) {
        DisplayEntity.BlockDisplayEntity display = new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, counter.getWorld());
        display.setSilent(true);
        display.setInvulnerable(true);
        display.addCommandTag(MARKER_TAG);
        counter.getWorld().spawnEntity(display);
        NbtCompound nbt = new NbtCompound();
        display.writeNbt(nbt);
        NbtCompound state = new NbtCompound();
        state.putString("Name", Registries.BLOCK.getId(block).toString());
        nbt.put("block_state", state);
        NbtCompound transformation = new NbtCompound();
        transformation.put("translation", vec(tx, ty, tz));
        transformation.put("scale", vec(sx, sy, sz));
        transformation.put("left_rotation", vec(0, 0, 0, 1));
        transformation.put("right_rotation", vec(0, 0, 0, 1));
        nbt.put("transformation", transformation);
        display.readNbt(nbt);
        display.refreshPositionAndAngles(counter.getX(), counter.getY(), counter.getZ(), 0.0f, 0.0f);
        if (!display.startRiding(counter, true)) {
            display.discard();
        }
    }

    private static void dropEntity(Entity entity) {
        if (entity == null) {
            return;
        }
        for (Entity passenger : java.util.List.copyOf(entity.getPassengerList())) {
            passenger.discard();
        }
        entity.discard();
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
        clearSidebar(player);
        if (books.showTab(id)) {
            player.networkHandler.sendPacket(new PlayerListHeaderS2CPacket(tabHeader(books), tabFooter()));
        } else {
            player.networkHandler.sendPacket(new PlayerListHeaderS2CPacket(Text.empty(), Text.empty()));
        }
    }

    /** Drops any sidebar already on the client. Saved preferences stay on disk and are not shown. */
    private void clearSidebar(ServerPlayerEntity player) {
        player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(
                objective, ScoreboardObjectiveUpdateS2CPacket.REMOVE_MODE));
        sidebarReady.remove(player.getUuid());
        sidebarLines.remove(player.getUuid());
    }

    private void refreshBossBar(MinecraftServer server, BankBooks books) {
        CurrencyDef note = books.primaryVirtual();
        BigDecimal tight = note == null ? null : books.tightness(note.id());
        BigDecimal copperPerDiamond = books.currency("copper") == null || books.currency("diamond") == null
                ? null : books.unitsPer("copper", "diamond");
        String rateText = BankPhrases.copperPerDiamond(books);
        bossBar.setName(Text.literal("汇率：" + rateText + " 铜锭/钻石    银行券兑付能力 " + CompactFormat.formatRatio(tight))
                .formatted(Formatting.WHITE));
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
        text.append(Text.literal("汇率\n").formatted(Formatting.GOLD));
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
            text.append(Text.literal("银行券兑付能力 ").formatted(Formatting.DARK_AQUA))
                    .append(Text.literal(CompactFormat.formatRatio(books.tightness(note.id())) + "\n").formatted(Formatting.WHITE));
        }
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
        display.addCommandTag(HOLO_TAG);
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
        header.append(Text.literal("汇率 ").formatted(Formatting.GOLD));
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
        header.append(Text.literal("\n手续费 " + fee(books)).formatted(Formatting.GRAY));
        return header;
    }

    private static Text tabFooter() {
        return Text.literal("右键银行网点打开柜台  ·  附近 /bank").formatted(Formatting.DARK_GRAY);
    }

    private static String fee(BankBooks books) {
        return books.feeFraction().multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
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
