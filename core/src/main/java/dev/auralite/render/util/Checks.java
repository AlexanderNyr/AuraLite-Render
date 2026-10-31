package dev.auralite.render.util;

/**
 * Argument and state validation with messages that name the offender.
 *
 * <p>Every backend and parser uses these instead of hand-rolled null checks, so
 * a failure says <i>which</i> value was wrong: {@code "width must be positive,
 * got 0"} instead of an NPE three frames later in code that never saw the pack.
 */
public final class Checks {
    private Checks() { }

    public static <T> T notNull(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return value;
    }

    public static String notEmpty(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    public static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive, got " + value);
        return value;
    }

    public static long positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive, got " + value);
        return value;
    }

    public static int nonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must be non-negative, got " + value);
        return value;
    }

    public static int inRange(int value, int low, int high, String name) {
        if (value < low || value > high) {
            throw new IllegalArgumentException(name + " must be in [" + low + ".." + high + "], got " + value);
        }
        return value;
    }

    public static void checkArgument(boolean condition, String message, Object... args) {
        if (!condition) throw new IllegalArgumentException(format(message, args));
    }

    public static void checkState(boolean condition, String message, Object... args) {
        if (!condition) throw new IllegalStateException(format(message, args));
    }

    private static String format(String message, Object[] args) {
        if (args == null || args.length == 0) return message;
        return String.format(java.util.Locale.ROOT, message, args);
    }
}
