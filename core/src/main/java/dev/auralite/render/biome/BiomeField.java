package dev.auralite.render.biome;

/**
 * A sliding 3D window of biome ids around the camera, at Minecraft's own
 * biome granularity of one id per 4x4x4 blocks.
 *
 * <p>This is the whole trick behind "more than one biome on screen". Iris
 * answers "which biome is this?" with one integer sampled at the camera. Here
 * the answer is a volume, so a fragment 120 blocks away in a swamp asks the
 * volume at its own position and gets the swamp, while the beach under the
 * camera gets the beach, in the same draw call and the same frame.
 *
 * <p>The window is a flat {@code int[]} rather than a sparse map because the
 * access pattern is a strided 8-neighbourhood read at fragment rate: a flat
 * array is cache-friendly, branch-free and copies to the GPU in one
 * {@code glTexSubImage3D} / buffer upload.
 */
public final class BiomeField {
    /** Cell value meaning "nothing ingested here yet". */
    public static final int UNKNOWN = -1;

    /** Blocks per cell edge. Minecraft's biome container granularity. */
    public static final int DEFAULT_CELL_SIZE = 4;

    /**
     * The world's bottom block, on the y axis Minecraft 1.18+ uses.
     *
     * <p>A chunk column is indexed by block y from here, which is why a window
     * that reaches below y = -64 would be addressing rows the world does not
     * have. Naming the constant here - rather than burying -64 in the reader -
     * is what lets the window arithmetic and the world agree on one frame.
     */
    public static final int MIN_BLOCK_Y = -64;

    private final int cellSize;
    private int originCX, originCY, originCZ;
    private int sizeCX, sizeCY, sizeCZ;
    private int[] ids = new int[0];
    private long version;
    private int filledCells;
    private long totalWrites;
    private long windowMoves;

    public BiomeField() { this(DEFAULT_CELL_SIZE); }

    public BiomeField(int cellSize) {
        if (cellSize < 1) throw new IllegalArgumentException("cellSize must be >= 1");
        this.cellSize = cellSize;
    }

    public int cellSize() { return cellSize; }
    public int sizeCX() { return sizeCX; }
    public int sizeCY() { return sizeCY; }
    public int sizeCZ() { return sizeCZ; }
    public int originCX() { return originCX; }
    public int originCY() { return originCY; }
    public int originCZ() { return originCZ; }
    public long version() { return version; }
    public int filledCells() { return filledCells; }
    public long totalWrites() { return totalWrites; }
    public long windowMoves() { return windowMoves; }
    public int cellCapacity() { return ids.length; }
    public long sizeBytes() { return (long) ids.length * 4; }

    /** (Re)allocates the window. Existing contents are dropped and marked unknown. */
    public BiomeField allocate(int cx, int cy, int cz) {
        if (cx < 1 || cy < 1 || cz < 1) throw new IllegalArgumentException("window must be at least 1x1x1");
        this.sizeCX = cx; this.sizeCY = cy; this.sizeCZ = cz;
        this.ids = new int[cx * cy * cz];
        java.util.Arrays.fill(ids, UNKNOWN);
        this.filledCells = 0;
        bumpVersion();
        return this;
    }

    /** Recentres the window on a cell coordinate, preserving cells that stay inside. */
    public BiomeField moveWindowTo(int newOriginCX, int newOriginCY, int newOriginCZ) {
        if (ids.length == 0) {
            originCX = newOriginCX; originCY = newOriginCY; originCZ = newOriginCZ;
            return this;
        }
        if (newOriginCX == originCX && newOriginCY == originCY && newOriginCZ == originCZ) return this;
        windowMoves++;
        int[] next = new int[ids.length];
        java.util.Arrays.fill(next, UNKNOWN);
        int carried = 0;
        for (int z = 0; z < sizeCZ; z++) {
            int wz = originCZ + z;
            int nz = wz - newOriginCZ;
            if (nz < 0 || nz >= sizeCZ) continue;
            for (int y = 0; y < sizeCY; y++) {
                int wy = originCY + y;
                int ny = wy - newOriginCY;
                if (ny < 0 || ny >= sizeCY) continue;
                for (int x = 0; x < sizeCX; x++) {
                    int wx = originCX + x;
                    int nx = wx - newOriginCX;
                    if (nx < 0 || nx >= sizeCX) continue;
                    int v = ids[idx(x, y, z)];
                    next[idx(nx, ny, nz)] = v;
                    if (v != UNKNOWN) carried++;
                }
            }
        }
        ids = next;
        filledCells = carried;
        originCX = newOriginCX; originCY = newOriginCY; originCZ = newOriginCZ;
        bumpVersion();
        return this;
    }

    public int idx(int x, int y, int z) { return (z * sizeCY + y) * sizeCX + x; }

    public boolean inRange(int cx, int cy, int cz) {
        if (ids.length == 0) return false;
        int x = cx - originCX, y = cy - originCY, z = cz - originCZ;
        return x >= 0 && y >= 0 && z >= 0 && x < sizeCX && y < sizeCY && z < sizeCZ;
    }

    public int worldToCellX(double wx) { return floorDiv((long) Math.floor(wx), cellSize); }
    public int worldToCellY(double wy) { return floorDiv((long) Math.floor(wy), cellSize); }
    public int worldToCellZ(double wz) { return floorDiv((long) Math.floor(wz), cellSize); }

    private static int floorDiv(long a, long b) { return (int) Math.floorDiv(a, b); }

    /** Id at a cell coordinate, or {@link #UNKNOWN} outside the window. */
    public int idAtCell(int cx, int cy, int cz) {
        if (!inRange(cx, cy, cz)) return UNKNOWN;
        return ids[idx(cx - originCX, cy - originCY, cz - originCZ)];
    }

    /** Id at a world coordinate, or {@link #UNKNOWN} outside the window. */
    public int idAtWorld(double wx, double wy, double wz) {
        return idAtCell(worldToCellX(wx), worldToCellY(wy), worldToCellZ(wz));
    }

    public boolean setIdAtCell(int cx, int cy, int cz, int biomeId) {
        if (!inRange(cx, cy, cz)) return false;
        int i = idx(cx - originCX, cy - originCY, cz - originCZ);
        if (ids[i] == biomeId) return false;
        if (ids[i] == UNKNOWN && biomeId != UNKNOWN) filledCells++;
        else if (ids[i] != UNKNOWN && biomeId == UNKNOWN) filledCells--;
        ids[i] = biomeId;
        totalWrites++;
        bumpVersion();
        return true;
    }

    public boolean setIdAtWorld(double wx, double wy, double wz, int biomeId) {
        return setIdAtCell(worldToCellX(wx), worldToCellY(wy), worldToCellZ(wz), biomeId);
    }

    public int unknownCount() { return ids.length - filledCells; }

    /** Raw backing array, for the packer. Never mutated by the packer. */
    public int[] rawIds() { return ids; }

    public void clear() {
        java.util.Arrays.fill(ids, UNKNOWN);
        filledCells = 0;
        bumpVersion();
    }

    private void bumpVersion() { version++; }

    @Override public String toString() {
        return "BiomeField{cell=" + cellSize + ", window=" + sizeCX + "x" + sizeCY + "x" + sizeCZ
                + ", origin=(" + originCX + "," + originCY + "," + originCZ + ")"
                + ", filled=" + filledCells + "/" + ids.length + ", v" + version + "}";
    }
}
