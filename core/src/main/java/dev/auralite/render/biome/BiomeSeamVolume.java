package dev.auralite.render.biome;

/**
 * The baked widened seam, as an object that knows three things the raw arrays
 * do not: which field it came from, whether it is still current, and how big it
 * is (BIOME_PLAN B5, step 3).
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>r47 could bake a volume and gate its staleness, but the two facts lived
 * beside each other: a caller could hold {@code int[] ids, int[] weights} and a
 * {@link BiomeSeamFreshness} and pair them wrongly, and nothing in the graph
 * could reach either. This class is that pairing, in one place, with the extent
 * from the field rather than from whatever the caller passed -- the volume's
 * height is the field's height by construction, because it is computed here and
 * nowhere else.
 *
 * <p>The object is deliberately not thread-safe and not shared: a bake is
 * single-threaded CPU work in this lane, and the GPU lane has the same data in a
 * texture whose freshness the pass gate checks. Making this concurrent would buy
 * nothing a receipt could show.
 */
public final class BiomeSeamVolume {

    private final BiomeField field;
    private final int cellSize;
    private final int sx, sy, sz;
    private int radius = 2;
    private final BiomeSeamFreshness freshness;
    private final int[] ids;
    private final int[] weights;
    private long bakeNanos;

    public BiomeSeamVolume(BiomeField field) {
        if (field == null) throw new IllegalArgumentException("a seam volume needs a field");
        if (field.sizeCX() <= 0 || field.sizeCY() <= 0 || field.sizeCZ() <= 0) {
            throw new IllegalArgumentException("the field has no window: call allocate() first");
        }
        this.field = field;
        this.cellSize = field.cellSize();
        this.sx = field.sizeCX();
        this.sy = field.sizeCY();
        this.sz = field.sizeCZ();
        int cells = sx * sy * sz;
        this.ids = new int[cells];
        this.weights = new int[cells];
        this.freshness = new BiomeSeamFreshness(field);
    }

    public BiomeField field() { return field; }
    public int cellSize() { return cellSize; }
    public int sizeX() { return sx; }
    public int sizeY() { return sy; }
    public int sizeZ() { return sz; }
    public int radius() { return radius; }
    public int[] ids() { return ids; }
    public int[] weights() { return weights; }
    public BiomeSeamFreshness freshness() { return freshness; }
    public long bakeNanos() { return bakeNanos; }

    public BiomeSeamVolume radius(int r) {
        BiomeSeam.checkRadius(r);
        this.radius = r;
        return this;
    }

    /**
     * Bakes the whole window with {@link BiomeSeamShader}'s rule and records the
     * field version it saw, so the next reader can ask whether it is current.
     * Returns the number of cells marked {@code IDS_OVER_TOPK}.
     */
    public int bake(BiomeRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("a bake needs a registry");
        readerSet(registry);
        int[] flat = new int[ids.length];
        for (int i = 0; i < flat.length; i++) flat[i] = field.rawIds()[i];
        long t0 = System.nanoTime();
        int marked = BiomeSeamShader.emitVolumeLenient(flat, sx, sy, sz, radius, ids, weights);
        bakeNanos = System.nanoTime() - t0;
        freshness.markBaked();
        return marked;
    }

    /**
     * Records that the graph just ran a pass which baked this volume. The graph
     * owns the pass, the volume owns the freshness record, and the version is
     * read here rather than passed in so the two cannot disagree about which
     * field state the bake saw.
     */
    public void markBakedByPass() {
        freshness.markBaked();
    }

    /** Reads the widened seam at a world position; refuses a stale volume by name. */
    public BiomeSample sample(BiomeRegistry registry, double wx, double wy, double wz) {
        freshness.checkFresh("biome_volume");
        readerSet(registry);
        // The volume is a WINDOW into the world, so the reader needs the
        // window's origin: a field centred on a player near the origin starts
        // at negative cells, and a reader that assumed cell 0 fetched a
        // different place entirely -- the seam appeared half a window away and
        // a flat swamp read as "nothing here".
        return currentReader.sample(ids, weights, sx, sy, sz, cellSize,
                field.originCX(), field.originCY(), field.originCZ(), wx, wy, wz);
    }

    /** Reads at a cell centre (exactly "one cell, one id" when the radius is 0). */
    public BiomeSample sampleCellCentre(BiomeRegistry registry, int cx, int cy, int cz) {
        freshness.checkFresh("biome_volume");
        readerSet(registry);
        return currentReader.sampleCellCentre(ids, weights, sx, sy, sz, cellSize, cx, cy, cz);
    }

    private BiomeSeamSampler currentReader;
    private void readerSet(BiomeRegistry registry) {
        if (currentReader == null || currentReader.registry() != registry) {
            currentReader = new BiomeSeamSampler(BiomeSeamShader.Layout.TOPK_RGBA8UI, registry);
        }
    }

    /**
     * How much of the seam this bake actually contains: cells that carry two or
     * more distinct biome ids, and the ids involved.
     *
     * <p>This exists because "the seam is in the frame" has to mean something
     * countable. A volume baked at radius 2 over terrain with ONE biome per
     * cell contains no blended cell at all, and a frame drawn from it looks
     * exactly like a working seam - the picture is just a flat colour. The
     * live gate needs the number: blended cells in the window, and how many
     * distinct ids the window holds, so that "no seam on screen" can be told
     * apart from "no seam in the data".
     */
    public int blendedCells() {
        int n = 0;
        for (int i = 0; i < ids.length; i++) {
            int a = ids[i] & 0xFF;
            int b = (ids[i] >>> 8) & 0xFF;
            if (a != 255 && b != 255 && b != a) n++;
        }
        return n;
    }

    /** The distinct biome ids this bake holds, ascending; 255 (no biome) excluded. */
    public int[] distinctIds() {
        boolean[] seen = new boolean[256];
        for (int word : ids) {
            for (int slot = 0; slot < 4; slot++) {
                int id = (word >>> (slot * 8)) & 0xFF;
                if (id != 255) seen[id] = true;
            }
        }
        int n = 0;
        for (boolean b : seen) if (b) n++;
        int[] out = new int[n];
        int k = 0;
        for (int id = 0; id < seen.length; id++) if (seen[id]) out[k++] = id;
        return out;
    }

    /** One line a receipt can quote: extent, radius, freshness and bake cost. */
    public String receipt() {
        int[] distinct = distinctIds();
        return "biome seam volume " + sx + "x" + sy + "x" + sz + " cells (cellSize=" + cellSize
                + ") R=" + radius + " " + freshness.receipt()
                + " bake=" + (bakeNanos / 1000L) + "us"
                + ", seam: " + blendedCells() + " blended cell(s) of " + (ids.length)
                + ", " + distinct.length + " distinct id(s) " + java.util.Arrays.toString(distinct);
    }
}
