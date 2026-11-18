package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;
import dev.jdguo.bank.net.UiSnapshotChunks;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.text.Text;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/** Client half of the optional bank screen. It only speaks if this jar is installed. */
public final class BankUiClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("bankmod-ui");
    private static final BankUiHandshake handshake = new BankUiHandshake();
    private static final UiSnapshotChunks.Assembler chunks = new UiSnapshotChunks.Assembler();
    static final int PROTOCOL = 2;
    static final Identifier HELLO = new Identifier("bankmod", "hello");
    static final Identifier ACTION = new Identifier("bankmod", "action");
    static final Identifier SYNC = new Identifier("bankmod", "sync");
    static final Identifier CLOSE = new Identifier("bankmod", "close_ui");
    static final Identifier SYNC_CHUNK = new Identifier("bankmod", "sync_chunk");

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(CLOSE, (client, handler, buf, response) -> {
            final String reason;
            try { reason = buf.readString(200); }
            catch (RuntimeException invalid) { LOGGER.warn("Invalid bank close packet", invalid); return; }
            client.execute(() -> {
                if (client.getNetworkHandler() != handler || client.player == null) return;
                chunks.clear();
                if (client.currentScreen instanceof BankAdminScreen || client.currentScreen instanceof BankPlayerListScreen || client.currentScreen instanceof BankTerminalScreen)
                    client.setScreen(null);
                client.player.sendMessage(Text.literal(reason), false);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(SYNC, (client, handler, buf, response) -> {
            final String json;
            try { json = buf.readString(32767); }
            catch (RuntimeException invalid) {
                LOGGER.warn("Rejected invalid bank UI packet", invalid);
                return;
            }
            client.execute(() -> {
                // A packet queued before disconnect must not open a screen on the next server.
                if (client.getNetworkHandler() != handler || client.player == null) return;
                chunks.clear();
                openSafely(client, json);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(SYNC_CHUNK, (client, handler, buf, response) -> {
            final int transfer, index, count;
            final String part;
            try {
                transfer = buf.readVarInt(); index = buf.readVarInt(); count = buf.readVarInt();
                part = buf.readString(UiSnapshotChunks.CHUNK_CHARS);
            } catch (RuntimeException invalid) {
                LOGGER.warn("Rejected invalid bank UI chunk packet", invalid);
                client.execute(() -> { if (client.getNetworkHandler() == handler) chunks.clear(); });
                return;
            }
            client.execute(() -> {
                if (client.getNetworkHandler() != handler || client.player == null) return;
                try {
                    String json = chunks.accept(transfer, index, count, part, System.currentTimeMillis());
                    if (json != null) openSafely(client, json);
                } catch (RuntimeException invalid) {
                    LOGGER.warn("Rejected incomplete bank UI snapshot", invalid);
                    client.player.sendMessage(Text.literal("银行界面数据接收不完整，请重新点击柜台。"), false);
                }
            });
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> { handshake.reset(); chunks.clear(); });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { handshake.reset(); chunks.clear(); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getNetworkHandler() == null || client.player == null) return;
            if (chunks.expire(System.currentTimeMillis())) {
                client.player.sendMessage(Text.literal("银行界面数据接收超时，请重新点击柜台。"), false);
            }
            if (!handshake.poll(true, ClientPlayNetworking.canSend(HELLO))) return;
            var buf = PacketByteBufs.create();
            buf.writeVarInt(PROTOCOL);
            ClientPlayNetworking.send(HELLO, buf);
        });
    }

    static void send(String action) {
        if (!ClientPlayNetworking.canSend(ACTION)) return;
        var buf = PacketByteBufs.create();
        buf.writeString(action, 200);
        ClientPlayNetworking.send(ACTION, buf);
    }

    private static void openSafely(MinecraftClient client, String json) {
        handshake.acknowledged();
        try { open(client, json); }
        catch (RuntimeException invalid) {
            LOGGER.error("Unable to open bank UI; check matching client/server mod versions", invalid);
            client.setScreen(null);
            client.player.sendMessage(Text.literal("银行界面数据无法打开，请检查客户端与服务器模组版本；详细错误已记录到 latest.log。"), false);
        }
    }

    private static void open(MinecraftClient client, String json) {
        JsonObject data = JsonParser.parseString(json).getAsJsonObject();
        if (BankSnapshot.admin(data)) {
            if (data.has("adminPlayers")) {
                if (client.currentScreen instanceof BankPlayerListScreen screen) screen.show(data);
                else client.setScreen(new BankPlayerListScreen(data));
                return;
            }
            if (client.currentScreen instanceof BankAdminScreen screen) {
                screen.show(data);
                return;
            }
            client.setScreen(new BankAdminScreen(data));
            return;
        }
        if (client.currentScreen instanceof BankTerminalScreen screen) {
            screen.show(data);
            return;
        }
        client.setScreen(new BankTerminalScreen(data));
    }
}
