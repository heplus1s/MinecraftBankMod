package dev.jdguo.bank.outlet;

import java.util.UUID;

/** A named kiosk. The block is the right-click target; the hologram floats above it. */
public final class OutletPoint {
    private final String name;
    private String world;
    private int x;
    private int y;
    private int z;
    private double radius;
    private double lift;
    private boolean hologram;
    private UUID hologramId;
    private UUID markerId;

    public OutletPoint(String name, String world, int x, int y, int z, double radius, double lift,
                       boolean hologram, UUID hologramId) {
        this(name, world, x, y, z, radius, lift, hologram, hologramId, null);
    }

    public OutletPoint(String name, String world, int x, int y, int z, double radius, double lift,
                       boolean hologram, UUID hologramId, UUID markerId) {
        this.name = name;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
        this.lift = lift;
        this.hologram = hologram;
        this.hologramId = hologramId;
        this.markerId = markerId;
    }

    public String name() { return name; }
    public String world() { return world; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public double radius() { return radius; }
    public double lift() { return lift; }
    public boolean hologram() { return hologram; }
    public UUID hologramId() { return hologramId; }
    public UUID markerId() { return markerId; }

    public void relocate(String world, int x, int y, int z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.hologramId = null;
        this.markerId = null;
    }

    public void setRadius(double radius) { this.radius = radius; }
    public void setLift(double lift) { this.lift = lift; }
    public void setHologram(boolean hologram) { this.hologram = hologram; }
    public void setHologramId(UUID hologramId) { this.hologramId = hologramId; }
    public void setMarkerId(UUID markerId) { this.markerId = markerId; }

    public boolean sameBlock(String world, int x, int y, int z) {
        return this.world.equals(world) && this.x == x && this.y == y && this.z == z;
    }

    public double distanceSquared(String world, double px, double py, double pz) {
        if (!this.world.equals(world)) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = px - (x + 0.5);
        double dy = py - (y + 0.5);
        double dz = pz - (z + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }
}
