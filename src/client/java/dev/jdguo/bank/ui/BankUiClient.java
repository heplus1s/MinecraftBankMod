package dev.jdguo.bank.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/** Client half of the optional bank screen. It only speaks if this jar is installed. */
public final class BankUiClient implements ClientModInitializer {
    static final int PROTOCOL = 2;
    static final Identifier HELLO = new Identifier("bankmod", "hello");
    static final Identifier ACTION = new Identifier("bankmod", "action");
    static final Identifier SYNC = new Identifier("bankmod", "sync");

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(SYNC, (client, handler, buf, response) -> {
            String json = buf.readString(32767);
            client.execute(() -> open(client, json));
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!ClientPlayNetworking.canSend(HELLO)) return;
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

    private static void open(MinecraftClient client, String json) {
        JsonObject data = JsonParser.parseString(json).getAsJsonObject();
        if (BankSnapshot.admin(data)) {
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
