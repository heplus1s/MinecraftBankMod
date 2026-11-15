package dev.jdguo.bank.display;

import java.util.Set;
import java.util.UUID;

/**
 * Decides which loaded entities are leftover bank counters.
 * A registered outlet's marker, its hologram, and the block displays riding that marker stay.
 * Everything else tagged as a bank outlet display is removed, including after the outlet list is empty.
 */
public final class OutletDisplaySweep {
    private OutletDisplaySweep() {}

    public static boolean shouldDiscard(Set<String> tags, UUID self, UUID vehicle, Set<UUID> kept) {
        if (tags == null || kept == null) {
            return false;
        }
        boolean outlet = tags.contains(DisplayService.MARKER_TAG) || tags.contains(DisplayService.HOLO_TAG);
        if (!outlet) {
            return false;
        }
        if (self != null && kept.contains(self)) {
            return false;
        }
        return vehicle == null || !kept.contains(vehicle);
    }
}
