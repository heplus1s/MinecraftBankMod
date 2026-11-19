package dev.jdguo.bank.net;

import dev.jdguo.bank.api.MarketSettlement;
import dev.jdguo.bank.ledger.BankBooks;
import dev.jdguo.bank.persistence.BankRuntime;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional UI channel. Packets are sent only after the client registers {@link #SYNC}
 * and says hello. Vanilla clients never register it, so they stay on the chest GUI.
 */
public final class BankUiNetwork {
    public static final int PROTOCOL = 2;
    public static final Identifier HELLO = new Identifier("bankmod", "hello");
    public static final Identifier ACTION = new Identifier("bankmod", "action");
    public static final Identifier SYNC = new Identifier("bankmod", "sync");
    public static final Identifier CLOSE = new Identifier("bankmod", "close_ui");
    public static final Identifier SYNC_CHUNK = new Identifier("bankmod", "sync_chunk");

    private static final Map<UUID, Integer> versions = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> chestFallback = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    private BankUiNetwork() {}

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (BankRuntime.state == null || server.getTicks() % 20 != 0) return;
            long checkpoint = BankRuntime.books().checkpoint();
            for (var entry : sessions.entrySet()) {
                ServerPlayerEntity online = server.getPlayerManager().getPlayer(entry.getKey());
                if (online == null) continue;
                // Permission changes need no ledger checkpoint; revoke idle sessions too.
                Session active = session(online);
                if (active == null || active.lastCheckpoint == checkpoint) continue;
                if (wantsUi(online)) sync(online);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(HELLO, (server, player, handler, buf, response) -> {
            final int version;
            try { version = buf.readVarInt(); } catch (RuntimeException invalid) { return; }
            server.execute(() -> {
                if (server.getPlayerManager().getPlayer(player.getUuid()) != player || player.networkHandler != handler) return;
                if (chestFallback.contains(player.getUuid())) return;
                versions.put(player.getUuid(), version);
                if (version != PROTOCOL) sessions.remove(player.getUuid());
                upgradeChest(player);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(ACTION, (server, player, handler, buf, response) -> {
            final String action;
            try { action = buf.readString(200); } catch (RuntimeException invalid) { return; }
            server.execute(() -> {
                if (server.getPlayerManager().getPlayer(player.getUuid()) != player || player.networkHandler != handler) return;
                if (wantsUi(player)) BankUiService.handle(player, action);
            });
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity current = server.getPlayerManager().getPlayer(handler.player.getUuid());
            if (current != null && current != handler.player) return;
            chestFallback.remove(handler.player.getUuid());
            versions.remove(handler.player.getUuid());
            sessions.remove(handler.player.getUuid());
        });
    }

    public static boolean wantsUi(ServerPlayerEntity player) {
        Integer version = versions.get(player.getUuid());
        return version != null && version == PROTOCOL && ServerPlayNetworking.canSend(player, SYNC);
    }

    /** Upgrade both /bank admin and a kiosk's management tab without losing context. */
    public static boolean upgradeChest(ServerPlayerEntity player) {
        if (!wantsUi(player) || !(player.currentScreenHandler instanceof dev.jdguo.bank.gui.BankScreenHandler chest)) return false;
        String outlet = chest.outletName();
        boolean admin = BankUiAccess.management(chest.remoteAdmin(), chest.managementPage(), player.hasPermissionLevel(2));
        player.closeHandledScreen();
        open(player, outlet, admin);
        return true;
    }

    public static void open(ServerPlayerEntity player, String outlet, boolean admin) {
        dev.jdguo.bank.instruments.NotePaper.refreshPersonalLabel(player.getMainHandStack(), BankRuntime.books());
        sessions.put(player.getUuid(), new Session(outlet, admin && player.hasPermissionLevel(2)));
        sync(player);
    }

    public static void sync(ServerPlayerEntity player) {
        Session session = session(player);
        if (session == null || !ServerPlayNetworking.canSend(player, SYNC)) {
            return;
        }
        BankBooks books = BankRuntime.books();
        String json = BankUiSnapshot.json(books, player.getUuid(), session.admin, session.page, session.confirm,
                MarketSettlement.externalPersonalMarket());
        com.google.gson.JsonObject payload = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        payload.addProperty("exchangeMessage", session.exchangeMessage);
        if (session.exchangeQuote != null) {
            var q = session.exchangeQuote;
            payload.addProperty("exchangeToken", session.exchangeToken);
            payload.addProperty("exchangePay", q.pay());
            payload.addProperty("exchangeReceive", q.receive());
            payload.addProperty("exchangeQuantity", q.quantity().toString());
        }
        PersonalQuantityUi.addPreview(payload, session);
        CentralPolicyUi.addPreview(payload, session);
        CentralAdjustmentUi.addPreview(payload, session);
        if (session.admin && session.fundingPreview != null) payload.add("fundingPreview", session.fundingPreview);
        if (session.admin && player.hasPermissionLevel(2)) payload.addProperty("tollAccountSupported", true);
        if (session.admin && player.hasPermissionLevel(2) && session.tollAccountOpen) {
            payload.addProperty("tollBalance", dev.jdguo.bank.ledger.Money.stored(books.tollAccountBalance()));
            if (session.tollSweepExpected != null && System.currentTimeMillis() <= session.tollSweepExpires)
                payload.addProperty("tollSweepExpected", dev.jdguo.bank.ledger.Money.stored(session.tollSweepExpected));
            var rows = books.recentSystemTollPayments();
            var server = player.getServer();
            for (var element : rows) {
                var row = element.getAsJsonObject();
                try {
                    UUID id = UUID.fromString(row.get("payer").getAsString());
                    var online = server.getPlayerManager().getPlayer(id);
                    String name = online == null ? null : online.getGameProfile().getName();
                    if (name == null && row.has("payerName")) name = row.get("payerName").getAsString();
                    if (name == null && server.getUserCache() != null)
                        name = server.getUserCache().getByUuid(id).map(profile -> profile.getName()).orElse(null);
                    row.addProperty("name", name != null && !name.isBlank() ? name : "未知玩家 (" + id.toString().substring(0, 8) + ")");
                    String token = row.get("tender").getAsString();
                    var currency = books.currency(token);
                    var personal = token.startsWith("personal:") ? books.personalNoteById(UUID.fromString(token.substring(9))) : null;
                    row.addProperty("tenderName", currency != null ? currency.name() : personal != null ? personal.name() : token);
                } catch (IllegalArgumentException | NullPointerException invalid) {
                    row.addProperty("name", "未知玩家");
                }
                row.remove("payer");
                row.remove("payerName");
            }
            payload.add("tollPayments", rows);
        }
        if (session.admin && session.playerListOpen && player.hasPermissionLevel(2))
            payload.add("adminPlayers", AdminPlayerDirectory.page(player, books, session.playerListQuery, session.playerListPage, session.playerRoster));
        String encoded = payload.toString();
        if (encoded.length() > 32767) {
            if (encoded.length() > UiSnapshotChunks.MAX_CHARS || !ServerPlayNetworking.canSend(player, SYNC_CHUNK)) {
                fallbackToChest(player, session);
                return;
            }
            var chunks = UiSnapshotChunks.split(encoded);
            int transfer = ++session.snapshotTransfer;
            for (int i = 0; i < chunks.size(); i++) {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeVarInt(transfer);
                buf.writeVarInt(i);
                buf.writeVarInt(chunks.size());
                buf.writeString(chunks.get(i), UiSnapshotChunks.CHUNK_CHARS);
                ServerPlayNetworking.send(player, SYNC_CHUNK, buf);
            }
        } else {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(encoded, 32767);
            ServerPlayNetworking.send(player, SYNC, buf);
        }
        session.lastCheckpoint = books.checkpoint();
    }

    private static void fallbackToChest(ServerPlayerEntity player, Session session) {
        chestFallback.add(player.getUuid());
        versions.remove(player.getUuid());
        sessions.remove(player.getUuid());
        BankRuntime.LOGGER.warn("Using vanilla bank menu for {}: UI snapshot exceeds negotiated transport limit", player.getUuid());
        player.sendMessage(net.minecraft.text.Text.literal("银行数据超出当前图形界面传输容量，已切换箱子界面；更新客户端后可重新连接。"), false);
        dev.jdguo.bank.gui.BankMenus.openChest(player, session.outlet, session.admin,
                session.admin ? dev.jdguo.bank.gui.BankScreenHandler.Page.MANAGEMENT
                        : dev.jdguo.bank.gui.BankScreenHandler.Page.SAVINGS);
    }

    static Session session(ServerPlayerEntity player) {
        Session active = sessions.get(player.getUuid());
        if (active != null && !BankUiAccess.sessionAllowed(active.admin, player.hasPermissionLevel(2))) {
            sessions.remove(player.getUuid());
            String reason = "银行管理权限已撤销，请重新打开普通银行界面。";
            if (ServerPlayNetworking.canSend(player, CLOSE)) {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeString(reason, 200);
                ServerPlayNetworking.send(player, CLOSE, buf);
            } else {
                // Legacy clients still receive the vanilla close-screen packet.
                player.closeHandledScreen();
                player.sendMessage(net.minecraft.text.Text.literal(reason), false);
            }
            return null;
        }
        return active;
    }

    static void forget(ServerPlayerEntity player) {
        sessions.remove(player.getUuid());
    }

    static final class Session {
        final String outlet;
        final boolean admin;
        String page = "savings";
        boolean confirm;
        dev.jdguo.bank.ledger.CentralAdjustment centralAdjustment;
        long centralAdjustmentExpires;
        String centralAdjustmentToken = "";
        dev.jdguo.bank.ledger.CentralIssuePlan centralIssuePlan;
        long centralIssueExpires;
        dev.jdguo.bank.ledger.PersonalQuantityPlan personalQuantityPlan;
        String personalQuantityToken = "";
        long personalQuantityExpires;
        com.google.gson.JsonObject fundingPreview;
        long fundingExpires;
        boolean tollAccountOpen;
        java.math.BigDecimal tollSweepExpected;
        long tollSweepExpires;
        String treasuryCommand;
        long treasuryExpires;
        dev.jdguo.bank.ledger.HardExchangeQuote exchangeQuote;
        String exchangeToken = "";
        String exchangeMessage = "";
        long exchangeExpires;
        long lastCheckpoint = -1;
        int snapshotTransfer;
        final AdminPlayerDirectory.CachedRoster playerRoster = new AdminPlayerDirectory.CachedRoster();
        boolean playerListOpen;
        int playerListPage;
        String playerListQuery = "";

        Session(String outlet, boolean admin) {
            this.outlet = outlet;
            this.admin = admin;
            this.page = admin ? "management" : "savings";
        }
    }
}
