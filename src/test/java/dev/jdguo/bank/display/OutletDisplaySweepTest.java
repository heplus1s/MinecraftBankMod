package dev.jdguo.bank.display;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutletDisplaySweepTest {
    private final UUID counter = UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc1");
    private final UUID desk = UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc2");
    private final UUID sign = UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc3");
    private final UUID stranger = UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc4");

    @Test
    void anEmptyOutletListDropsEveryBankDisplay() {
        Set<UUID> kept = Set.of();
        assertTrue(OutletDisplaySweep.shouldDiscard(Set.of(DisplayService.MARKER_TAG), counter, null, kept));
        assertTrue(OutletDisplaySweep.shouldDiscard(Set.of(DisplayService.MARKER_TAG), desk, counter, kept));
        assertTrue(OutletDisplaySweep.shouldDiscard(Set.of(DisplayService.HOLO_TAG), sign, null, kept));
        assertFalse(OutletDisplaySweep.shouldDiscard(Set.of("minecraft"), stranger, null, kept));
        assertFalse(OutletDisplaySweep.shouldDiscard(Set.of(), stranger, null, kept));
    }

    @Test
    void aRegisteredCounterKeepsItsMarkerHologramAndBlockDisplays() {
        Set<UUID> kept = Set.of(counter, sign);
        assertFalse(OutletDisplaySweep.shouldDiscard(Set.of(DisplayService.MARKER_TAG), counter, null, kept));
        assertFalse(OutletDisplaySweep.shouldDiscard(Set.of(DisplayService.MARKER_TAG), desk, counter, kept));
        assertFalse(OutletDisplaySweep.shouldDiscard(Set.of(DisplayService.HOLO_TAG), sign, null, kept));
        assertTrue(OutletDisplaySweep.shouldDiscard(Set.of(DisplayService.MARKER_TAG), stranger, null, kept));
        assertTrue(OutletDisplaySweep.shouldDiscard(Set.of(DisplayService.HOLO_TAG), stranger, desk, kept));
    }
}
