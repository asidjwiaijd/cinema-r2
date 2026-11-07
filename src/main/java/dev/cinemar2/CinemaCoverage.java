package dev.cinemar2;

/** Shared bounds for screen packets, remote rendering and sound coverage. */
public final class CinemaCoverage {
    public static final int DEFAULT_DISTANCE = 256;
    public static final int MAX_DISTANCE = 512;
    private CinemaCoverage() {}
    public static int distance(int configured) { return Math.clamp(configured, DEFAULT_DISTANCE, MAX_DISTANCE); }
    public static double fullVolumeDistance(int width, int range) {
        return Math.min(distance(range) * .75, Math.max(128, Math.clamp(width, 4, 100) * 2));
    }
}
