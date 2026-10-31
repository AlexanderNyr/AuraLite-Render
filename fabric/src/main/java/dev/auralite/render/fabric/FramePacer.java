package dev.auralite.render.fabric;

/**
 * CPU-side frame pacing with measured, printable numbers.
 *
 * <p>Phase 4's "frame pacing" deliverable: a target frame time, an
 * exponential moving average of the real one, and a sleep that pads the
 * frame when it ran short. The receipt string is the whole API - no claimed
 * smoothness adjectives, only nanoseconds the loop actually measured.
 * vsync itself stays the driver's; pacing here is for the uncapped case and
 * for keeping the hot-reload loop from spinning at 1000 fps.
 */
public final class FramePacer {

    private final long targetNanos;
    private long frameStartNanos = -1L;
    private double emaFrameNanos = 0.0;
    private long frames;
    private long totalSleptNanos;

    /**
     * @param targetFps 0 or negative = uncapped (measure only).
     */
    public FramePacer(double targetFps) {
        this.targetNanos = targetFps > 0.0
                ? (long) (1_000_000_000.0 / targetFps)
                : 0L;
    }

    public void beginFrame() {
        frameStartNanos = System.nanoTime();
    }

    /** Ends the frame, pads to the target, returns the real frame time in nanos. */
    public long endFrame() {
        long now = System.nanoTime();
        long start = frameStartNanos < 0 ? now : frameStartNanos;
        long work = now - start;
        long slept = 0;
        if (targetNanos > 0 && work < targetNanos) {
            long deadline = start + targetNanos;
            long sl = deadline - System.nanoTime();
            if (sl > 0) {
                try {
                    Thread.sleep(sl / 1_000_000L, (int) (sl % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                slept = System.nanoTime() - start - work;
            }
        }
        long frame = System.nanoTime() - start;
        frames++;
        totalSleptNanos += Math.max(0, slept);
        emaFrameNanos = frames == 1 ? frame : emaFrameNanos + (frame - emaFrameNanos) * 0.1;
        frameStartNanos = -1L;
        return frame;
    }

    public long frames() { return frames; }

    /** One-line receipt: target, EMA frame time in ms, sleeps in ms. */
    public String receipt() {
        return "pacer: frames=" + frames
                + " target=" + (targetNanos > 0 ? String.format(java.util.Locale.ROOT,
                        "%.2fms", targetNanos / 1.0e6) : "uncapped")
                + " ema=" + String.format(java.util.Locale.ROOT, "%.3fms", emaFrameNanos / 1.0e6)
                + " slept=" + String.format(java.util.Locale.ROOT, "%dms", totalSleptNanos / 1_000_000L);
    }
}
