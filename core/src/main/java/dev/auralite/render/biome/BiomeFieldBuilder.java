package dev.auralite.render.biome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps a {@link BiomeField} in step with the chunks the client has loaded.
 *
 * <p>Ingest is one 4x4x4x4 = 64-cell block per chunk section, which is exactly
 * Minecraft's biome container granularity, so nothing is resampled and no
 * information is lost. The window follows the camera in whole cells and
 * carries its contents across the move, so walking in a straight line costs
 * one new slice of cells per 4 blocks rather than a full re-ingest.
 */
public final class BiomeFieldBuilder {

    /** One chunk section's biomes: 4x4x4 cells covering 16x16x16 blocks. */
    public static final class SectionBiomes {
        public int chunkX, chunkZ, sectionY;
        /** 64 ids, indexed {@code (y * 4 + z) * 4 + x} in cell space. */
        public final int[] cellIds = new int[64];
        /** Ids are palette-resolved by the caller; -1 means "not loaded yet". */
        public boolean valid;

        public SectionBiomes(int chunkX, int sectionY, int chunkZ) {
            this.chunkX = chunkX; this.sectionY = sectionY; this.chunkZ = chunkZ;
            java.util.Arrays.fill(cellIds, BiomeField.UNKNOWN);
        }

        public void set(int x, int y, int z, int id) { cellIds[(y * 4 + z) * 4 + x] = id; }
        public int get(int x, int y, int z) { return cellIds[(y * 4 + z) * 4 + x]; }
        public void fill(int id) { java.util.Arrays.fill(cellIds, id); }

        public long key() {
            // 21 bits per axis is plenty for any world, and keeps the key unique.
            return (((long) chunkX & 0x1FFFFF) << 42) | (((long) sectionY & 0xFFFFF) << 21)
                    | ((long) chunkZ & 0x1FFFFF);
        }
    }

    private final BiomeField field;
    private final Map<Long, SectionBiomes> ingested = new HashMap<Long, SectionBiomes>();
    private final List<DirtyRegion> dirty = new ArrayList<DirtyRegion>();
    private int verticalMinSection = -16;
    private int verticalMaxSection = 20;
    private long ingestedSections;
    private long rejectedSections;
    private long carriedCells;

    public BiomeFieldBuilder(BiomeField field) { this.field = field; }

    public BiomeField field() { return field; }
    public long ingestedSections() { return ingestedSections; }
    public long rejectedSections() { return rejectedSections; }
    public long carriedCells() { return carriedCells; }
    public int cachedSectionCount() { return ingested.size(); }

    public BiomeFieldBuilder verticalRange(int minSection, int maxSection) {
        this.verticalMinSection = minSection;
        this.verticalMaxSection = maxSection;
        return this;
    }

    /**
     * Sizes the window for a render distance.
     *
     * <p>Horizontally: {@code (2 * radius + 1)} chunks, one extra cell of margin
     * so a sample on the window edge still has all eight corners inside.
     * Vertically: the configured section range.
     */
    public BiomeFieldBuilder sizeForRenderDistance(int radiusChunks, int minHeight, int maxHeight) {
        int cell = field.cellSize();
        int blocksAcross = (2 * radiusChunks + 1) * 16 + 2 * cell;
        int cx = blocksAcross / cell + 1;
        int cy = Math.max(1, (maxHeight - minHeight) / cell + 1);
        int cz = cx;
        field.allocate(cx, cy, cz);
        verticalMinSection = minHeight / 16;
        verticalMaxSection = maxHeight / 16;
        return this;
    }

    /** Recentres the window on a camera position, carrying whatever still fits. */
    public void recentre(double camX, double camY, double camZ) {
        int cell = field.cellSize();
        int halfX = field.sizeCX() / 2, halfY = field.sizeCY() / 2, halfZ = field.sizeCZ() / 2;
        int ccx = (int) Math.floorDiv((long) Math.floor(camX), cell) - halfX;
        int ccy = (int) Math.floorDiv((long) Math.floor(camY), cell) - halfY;
        int ccz = (int) Math.floorDiv((long) Math.floor(camZ), cell) - halfZ;
        if (ccx == field.originCX() && ccy == field.originCY() && ccz == field.originCZ()) return;
        int before = field.filledCells();
        field.moveWindowTo(ccx, ccy, ccz);
        carriedCells += field.filledCells() < before ? field.filledCells() : before;
        // Everything that survived the move is, by definition, unchanged; only the
        // newly exposed slice needs an upload, so mark the whole window dirty and
        // let coalescing reduce it to one box.
        dirty.add(new DirtyRegion(field.originCX(), field.originCY(), field.originCZ(),
                field.sizeCX(), field.sizeCY(), field.sizeCZ()));
    }

    /** Ingests one chunk section. Returns false when it fell outside the window. */
    public boolean ingest(SectionBiomes section) {
        if (!section.valid) { rejectedSections++; return false; }
        if (section.sectionY < verticalMinSection || section.sectionY > verticalMaxSection) {
            rejectedSections++;
            return false;
        }
        // A section whose cells all fall outside the window is not "accepted with
        // no effect": it is rejected, and it is not cached, or the cache fills
        // with data the field can never use and the receipt overstates progress.
        int baseCX = section.chunkX * 4, baseCZ = section.chunkZ * 4, baseCY = section.sectionY * 4;
        boolean intersects = field.inRange(baseCX, baseCY, baseCZ)
                || field.inRange(baseCX + 3, baseCY + 3, baseCZ + 3)
                || field.inRange(baseCX + 3, baseCY, baseCZ)
                || field.inRange(baseCX, baseCY + 3, baseCZ + 3);
        if (!intersects) {
            rejectedSections++;
            return false;
        }
        ingested.put(section.key(), section);
        ingestedSections++;

        int cell = field.cellSize();
        if (cell != 4) {
            // Non-4 cell sizes are a debugging configuration; resample by nearest cell.
            return ingestResampled(section);
        }

        boolean any = false;
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    int id = section.get(x, y, z);
                    if (field.setIdAtCell(baseCX + x, baseCY + y, baseCZ + z, id)) any = true;
                }
            }
        }
        if (any) {
            int minX = baseCX - field.originCX(), minY = baseCY - field.originCY(), minZ = baseCZ - field.originCZ();
            dirty.add(new DirtyRegion(
                    Math.max(0, minX), Math.max(0, minY), Math.max(0, minZ),
                    Math.min(4, field.sizeCX() - Math.max(0, minX)),
                    Math.min(4, field.sizeCY() - Math.max(0, minY)),
                    Math.min(4, field.sizeCZ() - Math.max(0, minZ))));
        }
        return any;
    }

    private boolean ingestResampled(SectionBiomes section) {
        boolean any = false;
        for (int by = 0; by < 16; by++) {
            for (int bz = 0; bz < 16; bz++) {
                for (int bx = 0; bx < 16; bx++) {
                    int wx = section.chunkX * 16 + bx;
                    int wy = section.sectionY * 16 + by;
                    int wz = section.chunkZ * 16 + bz;
                    int id = section.get(bx >> 2, by >> 2, bz >> 2);
                    if (field.setIdAtWorld(wx, wy, wz, id)) any = true;
                }
            }
        }
        if (any) {
            dirty.add(new DirtyRegion(field.originCX(), field.originCY(), field.originCZ(),
                    field.sizeCX(), field.sizeCY(), field.sizeCZ()));
        }
        return any;
    }

    /** Forgets sections outside the current window so the cache cannot grow forever. */
    public int evictOutsideWindow() {
        List<Long> dead = new ArrayList<Long>();
        for (Map.Entry<Long, SectionBiomes> e : ingested.entrySet()) {
            SectionBiomes s = e.getValue();
            int baseCX = s.chunkX * 4, baseCZ = s.chunkZ * 4, baseCY = s.sectionY * 4;
            boolean inside = field.inRange(baseCX, baseCY, baseCZ)
                    || field.inRange(baseCX + 3, baseCY + 3, baseCZ + 3);
            if (!inside) dead.add(e.getKey());
        }
        for (Long k : dead) ingested.remove(k);
        return dead.size();
    }

    /** Re-ingests everything cached after a window resize. */
    public int replayCache() {
        int n = 0;
        for (SectionBiomes s : new ArrayList<SectionBiomes>(ingested.values())) {
            if (ingest(s)) n++;
        }
        return n;
    }

    public int pendingDirtyCount() { return dirty.size(); }

    /** Returns at most {@code budget} coalesced regions and clears the pending list. */
    public List<DirtyRegion> drainDirty(int budget, int gap) {
        List<DirtyRegion> out = DirtyRegion.coalesce(dirty, budget, gap);
        dirty.clear();
        return out;
    }

    public void clearDirty() { dirty.clear(); }

    public String receipt() {
        return "biome ingest: " + ingestedSections + " sections accepted, "
                + rejectedSections + " rejected, " + ingested.size() + " cached, "
                + field.filledCells() + "/" + field.cellCapacity() + " cells filled, "
                + field.windowMoves() + " window moves";
    }
}
