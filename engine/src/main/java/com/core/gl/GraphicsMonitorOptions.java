package com.core.gl;

/** Analyzer refresh presets; milliseconds are carried by the launch contract. */
public final class GraphicsMonitorOptions {
    public static final int DEFAULT_REFRESH_MS = 2000;
    public static final int MIN_REFRESH_MS = 500;
    public static final int MAX_REFRESH_MS = 10000;
    private static final int[] REFRESH_PRESETS_MS = {500, 1000, 2000, 3000, 5000, 10000};
    private GraphicsMonitorOptions() {}
    public static int[] refreshPresets() { return REFRESH_PRESETS_MS.clone(); }
    public static int normalizeRefresh(int value) {
        if (value < MIN_REFRESH_MS || value > MAX_REFRESH_MS) return DEFAULT_REFRESH_MS;
        // Migrate previous custom intervals to the closest menu preset; ties prefer the shorter one.
        int nearest = REFRESH_PRESETS_MS[0];
        for (int preset : REFRESH_PRESETS_MS) {
            if (Math.abs(value - preset) < Math.abs(value - nearest)) nearest = preset;
        }
        return nearest;
    }
}
