package dev.auralite.render.util;

/**
 * Integer math every backend needs: alignment, mip chains, power-of-two tests.
 *
 * <p>Uniform-buffer offsets must honour {@code minUniformBufferOffsetAlignment},
 * mip chains must agree between the graph's memory budget and the backend that
 * allocates them, and all of that is one shared implementation rather than
 * three copies that can drift.
 */
public final class MathUtil {
    private MathUtil() { }

    public static int clamp(int value, int low, int high) {
        return value < low ? low : Math.min(value, high);
    }

    public static float clamp(float value, float low, float high) {
        return value < low ? low : Math.min(value, high);
    }

    public static boolean isPow2(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    /**
     * Rounds {@code value} up to a multiple of {@code alignment}, which must be
     * a positive power of two. Used for uniform/storage buffer offsets.
     */
    public static long alignUp(long value, int alignment) {
        Checks.checkArgument(isPow2(alignment), "alignment must be a positive power of two, got %d", alignment);
        Checks.checkArgument(value >= 0, "value must be non-negative, got %d", value);
        return (value + alignment - 1) & ~((long) alignment - 1);
    }

    public static int alignUp(int value, int alignment) {
        return (int) alignUp((long) value, alignment);
    }

    /** Levels in a full mip chain for a 2D extent, level 0 included. */
    public static int mipLevelCount(int width, int height) {
        Checks.positive(width, "width");
        Checks.positive(height, "height");
        int levels = 1, s = Math.max(width, height);
        while (s > 1) { s >>= 1; levels++; }
        return levels;
    }

    /** Extent of one axis at the given mip level, never below 1. */
    public static int mipExtent(int base, int level) {
        Checks.positive(base, "base");
        Checks.nonNegative(level, "level");
        return Math.max(1, base >> level);
    }
}
