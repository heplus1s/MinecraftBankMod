package dev.jdguo.bank.text;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class BankTalk {
    private BankTalk() {}

    public static boolean english(ServerPlayerEntity player) {
        return player != null && BankLocale.english(player.getUuid());
    }

    public static boolean english(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            return english(player);
        }
        return false;
    }

    public static Text text(boolean english, Spoken spoken) {
        return Text.literal(spoken.text(english)).formatted(formatting(spoken.tone()));
    }

    public static Text text(ServerPlayerEntity player, Spoken spoken) {
        return text(english(player), spoken);
    }

    public static Text text(ServerCommandSource source, Spoken spoken) {
        return text(english(source), spoken);
    }

    /** Vanilla click: while chat is open, this line replaces the input with {@code command}. */
    public static Text suggest(ServerPlayerEntity player, Spoken spoken, String command) {
        return text(player, spoken).copy().styled(style -> style.withClickEvent(
                new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command)));
    }

    public static Formatting formatting(Tone tone) {
        return switch (tone) {
            case GOLD -> Formatting.GOLD;
            case AQUA -> Formatting.AQUA;
            case GREEN -> Formatting.GREEN;
            case DARK_RED -> Formatting.DARK_RED;
            case RED -> Formatting.RED;
            case GRAY -> Formatting.GRAY;
        };
    }
}
