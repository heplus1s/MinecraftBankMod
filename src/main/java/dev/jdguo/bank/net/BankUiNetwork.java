package dev.jdguo.bank.net;

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

    private static final Map<UUID, Integer> versions = new ConcurrentHashMap<>();
    private static final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    private BankUiNetwork() {}

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(HELLO, (server, player, handler, buf, response) -> {
            int version = buf.readVarInt();
            server.execute(() -> {
                versions.put(player.getUuid(), version);
                if (version == PROTOCOL && player.currentScreenHandler instanceof dev.jdguo.bank.gui.BankScreenHandler chest) {
                    String outlet = chest.outletName();
                    boolean admin = chest.remoteAdmin();
                    player.closeHandledScreen();
                    open(player, outlet, admin);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(ACTION, (server, player, handler, buf, response) -> {
            String action = buf.readString(200);
            server.execute(() -> BankUiService.handle(player, action));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            versions.remove(handler.player.getUuid());
            sessions.remove(handler.player.getUuid());
        });
    }

    public static boolean wantsUi(ServerPlayerEntity player) {
        Integer version = versions.get(player.getUuid());
        return version != null && version == PROTOCOL && ServerPlayNetworking.canSend(player, SYNC);
    }

    public static void open(ServerPlayerEntity player, String outlet, boolean admin) {
        sessions.put(player.getUuid(), new Session(outlet, admin));
        sync(player);
    }

    public static void sync(ServerPlayerEntity player) {
        Session session = sessions.get(player.getUuid());
        if (session == null || !ServerPlayNetworking.canSend(player, SYNC)) {
            return;
        }
        BankBooks books = BankRuntime.books();
        String json = BankUiSnapshot.json(books, player.getUuid(), session.admin, session.page, session.confirm);
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(json, 32767);
        ServerPlayNetworking.send(player, SYNC, buf);
    }

    static Session session(ServerPlayerEntity player) {
        return sessions.get(player.getUuid());
    }

    static void forget(ServerPlayerEntity player) {
        sessions.remove(player.getUuid());
    }

    static final class Session {
        final String outlet;
        final boolean admin;
        String page = "savings";
        boolean confirm;

        Session(String outlet, boolean admin) {
            this.outlet = outlet;
            this.admin = admin;
        }
    }
}
