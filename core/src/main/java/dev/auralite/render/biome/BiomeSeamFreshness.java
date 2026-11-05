package dev.auralite.render.biome;

/**
 * Tracks whether a baked biome volume still matches the field it came from --
 * the plan's {@code BIOME_VOLUME_STALE} (BIOME_PLAN B5, gate 4).
 *
 * <h2>Why a stale seam is the dangerous failure</h2>
 *
 * <p>A seam baked from yesterday's field does not look broken. It is smooth,
 * plausible, and wrong at exactly the borders the pack went to the trouble of
 * widening -- a chunk loaded five frames ago shows the old neighbours, and the
 * only symptom is a blend that lags the world. Bugs of that shape survive visual
 * review indefinitely, so the rule here is that staleness must be a <em>named
 * refusal</em> at the moment a frame reads the volume, not a note in a log.
 *
 * <p>The bookkeeping is deliberately one number: {@link BiomeField#version()}
 * increments on every write, so "is the bake current" is an equality test with
 * no dirty-region replay, no per-instance allocation, and no way to forget to
 * mark something. The pass records the version it baked ({@link #markBaked});
 * a consumer calls {@link #checkFresh} and gets the refusal by name.
 *
 * <p>What a caller does about it is its own decision -- bake again, skip the
 * read, fall back to the exact-id field -- and all three are legitimate. The
 * class refuses only the silent fourth: reading anyway.
 */
public final class BiomeSeamFreshness {

    private final BiomeField field;
    private long bakedVersion = Long.MIN_VALUE;
    private long bakeCount;
    private long stalenessSeen;

    public BiomeSeamFreshness(BiomeField field) {
        if (field == null) throw new IllegalArgumentException("a freshness tracker needs a field");
        this.field = field;
    }

    public BiomeField field() { return field; }

    /** Called by the producer after a bake completes, with the version it saw. */
    public void markBaked() {
        bakedVersion = field.version();
        bakeCount++;
    }

    /** True when nothing has been written since the last completed bake. */
    public boolean isFresh() { return bakedVersion == field.version(); }

    /** True when a bake has happened at all; a volume never baked is not "fresh". */
    public boolean hasBaked() { return bakedVersion != Long.MIN_VALUE; }

    public long bakedVersion() { return bakedVersion; }
    public long bakeCount() { return bakeCount; }
    public long stalenessSeen() { return stalenessSeen; }

    /**
     * The read gate: throws {@code BIOME_VOLUME_STALE} when the volume a
     * consumer is about to read was not baked from the field's current state.
     * Counted, so a receipt can report how often the frame path had to be
     * stopped -- a number that should be zero in a correct host and is
     * therefore worth watching rather than hiding.
     *
     * @param consumer what is reading, named in the refusal so the receipt
     *                 points at the pass instead of at the seam
     */
    public void checkFresh(String consumer) {
        if (!hasBaked()) {
            stalenessSeen++;
            throw new IllegalStateException("BIOME_VOLUME_STALE: '" + consumer
                    + "' is about to read a widened seam that was never baked");
        }
        if (!isFresh()) {
            stalenessSeen++;
            throw new IllegalStateException("BIOME_VOLUME_STALE: '" + consumer
                    + "' would read a seam baked at field version " + bakedVersion
                    + " while the field is at " + field.version()
                    + " (" + (field.version() - bakedVersion) + " write(s) since the bake)");
        }
    }

    /** {@link #checkFresh} without the throw: the one call that is allowed to ask first. */
    public boolean freshOrReport(String consumer) {
        if (isFresh()) return true;
        stalenessSeen++;
        return false;
    }

    /** One line a receipt can quote. */
    public String receipt() {
        return "bakes=" + bakeCount + " fresh=" + isFresh()
                + " fieldVersion=" + field.version() + " bakedVersion="
                + (hasBaked() ? Long.toString(bakedVersion) : "none")
                + " stalenessSeen=" + stalenessSeen;
    }
}
