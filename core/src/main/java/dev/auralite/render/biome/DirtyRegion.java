package dev.auralite.render.biome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A box of cells that changed, in cell coordinates.
 *
 * <p>Sub-image uploads are the difference between a biome field that costs
 * nothing after the first frame and one that re-uploads megabytes every time a
 * chunk loads. Regions are coalesced before upload because a chunk border
 * crossing otherwise produces dozens of 1-cell boxes and the driver call
 * overhead dominates the bytes.
 */
public final class DirtyRegion {
    public int minX, minY, minZ;
    public int sizeX, sizeY, sizeZ;

    public DirtyRegion(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.sizeX = Math.max(1, sizeX); this.sizeY = Math.max(1, sizeY); this.sizeZ = Math.max(1, sizeZ);
    }

    public static DirtyRegion cell(int cx, int cy, int cz) { return new DirtyRegion(cx, cy, cz, 1, 1, 1); }

    public int maxX() { return minX + sizeX; }
    public int maxY() { return minY + sizeY; }
    public int maxZ() { return minZ + sizeZ; }
    public long volume() { return (long) sizeX * sizeY * sizeZ; }

    public boolean contains(int cx, int cy, int cz) {
        return cx >= minX && cy >= minY && cz >= minZ && cx < maxX() && cy < maxY() && cz < maxZ();
    }

    public boolean intersects(DirtyRegion o) {
        return minX < o.maxX() && o.minX < maxX()
                && minY < o.maxY() && o.minY < maxY()
                && minZ < o.maxZ() && o.minZ < maxZ();
    }

    /** True when the two boxes touch or are within {@code gap} cells of each other. */
    public boolean touches(DirtyRegion o, int gap) {
        return minX - gap < o.maxX() && o.minX - gap < maxX()
                && minY - gap < o.maxY() && o.minY - gap < maxY()
                && minZ - gap < o.maxZ() && o.minZ - gap < maxZ();
    }

    public DirtyRegion union(DirtyRegion o) {
        int nMinX = Math.min(minX, o.minX), nMinY = Math.min(minY, o.minY), nMinZ = Math.min(minZ, o.minZ);
        int nMaxX = Math.max(maxX(), o.maxX()), nMaxY = Math.max(maxY(), o.maxY()), nMaxZ = Math.max(maxZ(), o.maxZ());
        return new DirtyRegion(nMinX, nMinY, nMinZ, nMaxX - nMinX, nMaxY - nMinY, nMaxZ - nMinZ);
    }

    /**
     * Coalesces a region list down to at most {@code budget} boxes.
     *
     * <p>Greedy: repeatedly merge the touching pair whose union wastes the least
     * volume, then, if the budget is still exceeded, merge the two largest
     * boxes regardless of adjacency. Deterministic -- the same input list
     * produces the same output list, which the ingest gate asserts.
     */
    public static List<DirtyRegion> coalesce(List<DirtyRegion> in, int budget, int gap) {
        List<DirtyRegion> out = new ArrayList<DirtyRegion>();
        for (DirtyRegion r : in) out.add(new DirtyRegion(r.minX, r.minY, r.minZ, r.sizeX, r.sizeY, r.sizeZ));
        if (budget <= 0) budget = 1;

        boolean merged = true;
        while (out.size() > 1 && merged) {
            merged = false;
            int bestI = -1, bestJ = -1;
            long bestWaste = Long.MAX_VALUE;
            for (int i = 0; i < out.size(); i++) {
                for (int j = i + 1; j < out.size(); j++) {
                    DirtyRegion a = out.get(i), b = out.get(j);
                    if (!a.touches(b, gap)) continue;
                    long waste = a.union(b).volume() - a.volume() - b.volume();
                    if (waste < bestWaste) { bestWaste = waste; bestI = i; bestJ = j; }
                }
            }
            if (bestI >= 0) {
                DirtyRegion u = out.get(bestI).union(out.get(bestJ));
                out.remove(bestJ);
                out.set(bestI, u);
                merged = true;
            }
        }

        while (out.size() > budget) {
            Collections.sort(out, new Comparator<DirtyRegion>() {
                @Override public int compare(DirtyRegion a, DirtyRegion b) {
                    int c = Long.compare(b.volume(), a.volume());
                    return c != 0 ? c : Integer.compare(a.minX, b.minX);
                }
            });
            DirtyRegion u = out.get(0).union(out.get(1));
            out.remove(1);
            out.set(0, u);
        }
        return out;
    }

    @Override public String toString() {
        return "[" + minX + "," + minY + "," + minZ + " +" + sizeX + "x" + sizeY + "x" + sizeZ + "]";
    }
}
