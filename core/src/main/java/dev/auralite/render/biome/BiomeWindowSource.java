package dev.auralite.render.biome;

/**
 * Keeps a {@link BiomeField} filled with the world's biomes, one sample per
 * cell, without knowing what a "world" is.
 *
 * <h2>Why the arithmetic lives in core and the reading does not</h2>
 *
 * <p>The part that can be wrong in ways no screenshot shows is here: which
 * cell a window starts at, when a rebuild is due, how often a rebuild is
 * allowed, and what happens where the world has no answer. None of that needs
 * a Minecraft class, so all of it can be gated by the host suite instead of
 * being read as text and hoped about. The lane-specific half is one function -
 * "the id at this block" - which {@code WorldBiomeSource} supplies from a live
 * chunk.
 *
 * <h2>The window follows the player, and only on a cell crossing</h2>
 *
 * <p>A 23&times;97&times;23 cell window at 4 blocks a cell is 51 313 samples; a
 * sample is a biome lookup, so doing it every frame is a visible hitch for
 * information that changes when the player walks four blocks. So: rebuild on
 * a cell crossing, and never more often than
 * {@link #minimumFramesBetweenRebuilds()} frames. Both rules are measured by
 * the suite rather than asserted.
 *
 * <p>No answer is an honest hole. A cell over an unloaded chunk, or one whose
 * biome the registry cannot name, becomes {@link BiomeField#UNKNOWN} rather
 * than the neighbouring id copied around - the bake already treats UNKNOWN as
 * "no contribution", which is the truthful thing for a seam to do.
 */
public final class BiomeWindowSource {

    /** The one thing this class cannot know: the id at a block. */
    public interface BlockSampler {
        /**
         * Every biome id in one vertical CHUNK COLUMN, bottom cell first, or
         * {@code null} when there is no column there.
         *
         * <p>This is the shape a real world answers in: a chunk owns a whole
         * column, and asking it per block means one call per cell (51 313 of
         * them for the reference window) through a cache that is allowed to
         * load what it is asked for. Asking a column instead says "this is the
         * column, read it or refuse it" - which is what lets the window avoid
         * generating a chunk just to discover it has no biome.
         *
         * <p>The default implementation refuses every column, so a sampler that
         * only knows {@link #idAt} keeps working unchanged.
         */
        default int[] column(int chunkX, int chunkZ, int cellSize, int cellY) { return null; }

        /**
         * Whether {@link #column} is a real answer for this sampler.
         *
         * <p>The distinction matters more than it looks. A sampler that says
         * "I have columns" and then returns {@code null} for one is a world
         * that does not have that chunk: the cell is a HOLE, and it must stay
         * one. Falling back to {@link #idAt} for the whole window in that case
         * was measured to fill every hole with a neighbour's biome - the seam
         * gate's "unknown" count went to zero on a world that was half
         * unloaded, which is the opposite of an honest field. A sampler that
         * says "I have no columns" keeps the per-cell path unchanged.
         */
        default boolean hasColumns() { return false; }

        /** @return the biome id at these block coordinates, or -1 when unknown */
        int idAt(int blockX, int blockY, int blockZ);
    }

    /** Frames between two rebuilds: a frame budget, not a wall clock. */
    public static final int MIN_FRAMES_BETWEEN_REBUILDS = 20;

    private final BiomeField field;
    private final BlockSampler sampler;
    private final int cellsX, cellsY, cellsZ;

    private int builtCellX = Integer.MIN_VALUE;
    private int builtCellY = Integer.MIN_VALUE;
    private int builtCellZ = Integer.MIN_VALUE;
    private long lastBuildFrame = Long.MIN_VALUE;
    private long builds;
    private long cellsWritten;
    private long lastUnmapped;
    private long lastUnloaded;
    private long lastColumnsRefused;
    private long retriesAfterEmptyWindow;
    private int lastChanged;
    private String lastOutcome = "never rebuilt";

    /**
     * @param field   the field to fill; its cell size decides the sampling grid
     * @param sampler where the ids come from
     * @param cellsX,cellsY,cellsZ the window's extent in cells, matching the
     *        pack's `biome-volume` declaration
     */
    public BiomeWindowSource(BiomeField field, BlockSampler sampler,
                             int cellsX, int cellsY, int cellsZ) {
        if (field == null) throw new IllegalArgumentException("a window source needs a field");
        if (sampler == null) throw new IllegalArgumentException("a window source needs a sampler");
        if (cellsX <= 0 || cellsY <= 0 || cellsZ <= 0) {
            throw new IllegalArgumentException("a window of " + cellsX + "x" + cellsY + "x" + cellsZ
                    + " cells is not a window");
        }
        this.field = field;
        this.sampler = sampler;
        this.cellsX = cellsX;
        this.cellsY = cellsY;
        this.cellsZ = cellsZ;
    }

    public BiomeField field() { return field; }
    public int minimumFramesBetweenRebuilds() { return MIN_FRAMES_BETWEEN_REBUILDS; }
    public long builds() { return builds; }
    public long cellsWritten() { return cellsWritten; }
    public long lastUnmapped() { return lastUnmapped; }
    public long lastUnloaded() { return lastUnloaded; }
    public int lastChanged() { return lastChanged; }
    /** Chunk columns the world refused to answer on the last rebuild. */
    public long lastColumnsRefused() { return lastColumnsRefused; }
    /** Rebuilds skipped because nothing was answered - the retry counter. */
    public long retriesAfterEmptyWindow() { return retriesAfterEmptyWindow; }
    public String lastOutcome() { return lastOutcome; }

    /**
     * The window's first cell on one axis, for a world position: the window is
     * centred on the position's own cell, so it begins half its extent before.
     */
    public static int originCellFor(double worldCoord, int cells, int cellSize) {
        if (cellSize <= 0) throw new IllegalArgumentException("cell size " + cellSize);
        int centreCell = Math.floorDiv((int) Math.floor(worldCoord), cellSize);
        return centreCell - cells / 2;
    }

    /** True when the window does not already cover the position's cell. */
    public boolean needsRebuild(double wx, double wy, double wz) {
        if (builtCellX == Integer.MIN_VALUE) return true;
        return field.worldToCellX(wx) != builtCellX
                || field.worldToCellY(wy) != builtCellY
                || field.worldToCellZ(wz) != builtCellZ;
    }

    /**
     * Rebuilds when the position left the window and the throttle allows it.
     *
     * @param frameIndex a monotonically increasing frame counter
     * @return cells whose value actually changed; 0 when nothing was rebuilt,
     *         which is not the same thing as "rebuild found no change" and is
     *         told apart by {@link #lastOutcome()}
     */
    public int updateFrom(double wx, double wy, double wz, long frameIndex) {
        lastChanged = 0;
        if (!needsRebuild(wx, wy, wz)) {
            lastOutcome = "window already covers this cell";
            return 0;
        }
        // `builds > 0` first, and not as a shortcut: `frameIndex - lastBuildFrame`
        // with lastBuildFrame at Long.MIN_VALUE overflows to a negative number,
        // so a first-ever update would look throttled forever. The suite caught
        // that with a fresh counter, which is why the guard is written out.
        if (builds > 0 && frameIndex - lastBuildFrame < MIN_FRAMES_BETWEEN_REBUILDS) {
            lastOutcome = "throttled (" + (frameIndex - lastBuildFrame) + " of "
                    + MIN_FRAMES_BETWEEN_REBUILDS + " frames)";
            return 0;
        }
        return rebuild(wx, wy, wz, frameIndex);
    }

    /** Rebuilds regardless of the throttle; the throttle is {@link #updateFrom}'s. */
    public int rebuild(double wx, double wy, double wz, long frameIndex) {
        int cellSize = field.cellSize();
        int ox = originCellFor(wx, cellsX, cellSize);
        int oy = originCellFor(wy, cellsY, cellSize);
        int oz = originCellFor(wz, cellsZ, cellSize);
        if (field.sizeCX() != cellsX || field.sizeCY() != cellsY || field.sizeCZ() != cellsZ) {
            field.allocate(cellsX, cellsY, cellsZ);
        }
        field.moveWindowTo(ox, oy, oz);

        int changed = 0;
        int unmapped = 0;
        int unloaded = 0;
        int refused = 0;
        int half = cellSize / 2;
        int columns = 0;
        // A real world answers by COLUMN, and one column usually covers the
        // whole vertical extent of the window: 23x23 columns instead of 51 313
        // per-block calls through the chunk cache. A sampler that has no column
        // to give (the fixtures, a world with no chunks loaded at all) falls
        // back to the per-cell rule below, so this is an optimisation of the
        // same contract and never a second one.
        boolean byColumn = sampler.hasColumns();
        for (int cz = 0; byColumn && cz < cellsZ; cz++) {
            for (int cx = 0; cx < cellsX; cx++) {
                int bx = (ox + cx) * cellSize + half;
                int bz = (oz + cz) * cellSize + half;
                int[] col = sampler.column(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16),
                        cellSize, oy);
                if (col == null) {
                    // The world does not have this chunk. Its cells are holes
                    // with their own reason - never the last column's biome,
                    // and never a per-block trip that would generate it.
                    refused++;
                    for (int cy = 0; cy < cellsY; cy++) {
                        if (field.setIdAtCell(ox + cx, oy + cy, oz + cz, BiomeField.UNKNOWN)) {
                            changed++;
                        }
                        unloaded++;
                    }
                    continue;
                }
                columns++;
                for (int cy = 0; cy < cellsY; cy++) {
                    int id = cellYInColumn(col, oy + cy, cellSize, half);
                    if (id < 0) {
                        if (id == UNLOADED) unloaded++;
                        else unmapped++;
                        id = BiomeField.UNKNOWN;
                    }
                    if (field.setIdAtCell(ox + cx, oy + cy, oz + cz, id)) changed++;
                }
            }
        }
        if (byColumn && columns == 0) {
            // No "did we have data before" test: the r63 version of this line
            // required builds > 0 || filledCells > 0, which is FALSE at spawn,
            // so the first-ever unanswered pass fell through, incremented
            // `builds`, printed "window NOT stamped, retry due" - and armed the
            // throttle for a retry that could never be needed, because the
            // stamp was set anyway by the fall-through path below.

            // NOTHING was answered. Overwriting the window with holes and
            // stamping it as built is how the r62 session ended up with a
            // permanently empty field: the first bake ran at spawn, before the
            // client's chunk pool held a single column at the new position,
            // every one of those 529 lookups was refused, and the next rebuild
            // was refused by the *window* rule - the player had not crossed a
            // cell - so the seam stayed empty for the rest of the session.
            // The throttle below is what paces this retry instead.
            lastBuildFrame = frameIndex;
            lastChanged = 0;
            lastUnmapped = 0;
            lastUnloaded = 0;
            lastColumnsRefused = refused;
            retriesAfterEmptyWindow++;
            lastOutcome = "no chunk column answered (" + refused
                    + " refused); previous window kept, retrying on the throttle";
            return 0;
        }
        if (byColumn) {
            // Every column was answered: no per-block call is needed, and the
            // receipt says so with a count the caller can check.
            //
            // The cell stamp is what suppresses the next rebuild, so it is only
            // earned by a window that got data. A pass where the world refused
            // every column leaves it un-stamped and the retry is due the moment
            // the throttle allows it - the fix for the r62 session, where the
            // first bake at spawn saw no chunks at all and the field then sat
            // empty for four minutes because the stamp said "covered".
            if (columns > 0) {
                builtCellX = field.worldToCellX(wx);
                builtCellY = field.worldToCellY(wy);
                builtCellZ = field.worldToCellZ(wz);
            }
            lastBuildFrame = frameIndex;
            builds++;
            cellsWritten += (long) cellsX * cellsY * cellsZ;
            lastChanged = changed;
            lastUnmapped = unmapped;
            lastUnloaded = unloaded;
            lastColumnsRefused = refused;
            lastOutcome = "rebuilt from " + columns + " of " + (cellsX * cellsZ)
                    + " chunk columns: " + changed + " changed, " + unmapped
                    + " unmapped, " + unloaded + " unloaded, " + refused
                    + " column(s) refused"
                    + (columns == 0 ? "; window NOT stamped, retry due" : "");
            return changed;
        }
        for (int cz = 0; cz < cellsZ; cz++) {
            for (int cy = 0; cy < cellsY; cy++) {
                for (int cx = 0; cx < cellsX; cx++) {
                    int id = sampler.idAt((ox + cx) * cellSize + half,
                            (oy + cy) * cellSize + half,
                            (oz + cz) * cellSize + half);
                    if (id < 0) {
                        // Two different reasons to have no answer, counted
                        // apart: the sampler reports -1 for both, and the
                        // caller knows which. The field only needs one.
                        if (id == UNLOADED) unloaded++;
                        else unmapped++;
                        id = BiomeField.UNKNOWN;
                    }
                    if (field.setIdAtCell(ox + cx, oy + cy, oz + cz, id)) changed++;
                }
            }
        }

        builtCellX = field.worldToCellX(wx);
        builtCellY = field.worldToCellY(wy);
        builtCellZ = field.worldToCellZ(wz);
        lastBuildFrame = frameIndex;
        builds++;
        cellsWritten += (long) cellsX * cellsY * cellsZ;
        lastChanged = changed;
        lastUnmapped = unmapped;
        lastUnloaded = unloaded;
        lastOutcome = "rebuilt: " + changed + " changed, " + unmapped + " unmapped, "
                + unloaded + " unloaded";
        return changed;
    }

    /**
     * One cell's id out of a chunk column, or {@link #UNLOADED} when the column
     * does not reach that height.
     *
     * <p>The column is indexed by BLOCK y from the world's bottom (the same
     * frame Minecraft uses: y = -64 is index 0 in the overworld), and a column
     * that is shorter than the window answers UNLOADED for the cells it does
     * not cover rather than repeating its last value - a hole in the field has
     * to stay a hole, because the seam gate counts them.
     */
    private static int cellYInColumn(int[] col, int cellY, int cellSize, int half) {
        long by = (long) cellY * cellSize + half;
        long index = by - BiomeField.MIN_BLOCK_Y;
        if (index < 0 || index >= col.length) return UNLOADED;
        return col[(int) index];
    }

    /** Sampler result for "the world has nothing here" (not a biome id). */
    public static final int UNLOADED = -2;

    /** One line a receipt can quote. */
    public String receipt() {
        return "biome window " + cellsX + "x" + cellsY + "x" + cellsZ
                + " cells of " + field.cellSize() + " blocks, origin ("
                + field.originCX() + "," + field.originCY() + "," + field.originCZ() + ")"
                + ", " + field.filledCells() + " filled / " + field.unknownCount() + " unknown"
                + ", " + builds + " rebuild(s), " + field.windowMoves() + " window move(s)"
                + ", " + retriesAfterEmptyWindow + " empty-window retry(ies)"
                + ", last: " + lastOutcome;
    }
}
