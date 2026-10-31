package dev.auralite.render.biome;

import dev.auralite.render.rhi.GfxFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * Packs a {@link BiomeField} into GPU-uploadable texels.
 *
 * <p>Two layouts, because they trade different things:
 *
 * <ul>
 * <li>{@link Layout#ID_R32UI} -- one 32-bit id per cell. Exact, supports any
 *     id a modded registry can produce, and the shader does its own trilinear
 *     blend from eight fetches plus eight LUT fetches.</li>
 * <li>{@link Layout#TOPK_RGBA8UI} -- the four most likely ids per cell plus a
 *     matching RGBA8 weight texture. One fetch instead of eight and a quarter
 *     of the bandwidth, at the cost of pre-resolving the blend on the CPU and
 *     of a hard ceiling of 254 biome ids. The packer refuses this layout with
 *     a named reason rather than silently truncating ids.</li>
 * </ul>
 */
public final class BiomeFieldPacker {

    public enum Layout { ID_R32UI, TOPK_RGBA8UI }

    /** Sentinel written for a cell that has never been ingested. */
    public static final int UNKNOWN_TEXEL = 0xFFFFFFFF;
    public static final int UNKNOWN_TOPK = 255;
    public static final int MAX_TOPK_IDS = 254;

    private final BiomeField field;
    private final Layout layout;
    private final BiomeSampler sampler;

    public BiomeFieldPacker(BiomeField field, Layout layout, BiomeRegistry registry) {
        this.field = field;
        this.layout = layout;
        this.sampler = layout == Layout.TOPK_RGBA8UI ? new BiomeSampler(field, registry) : null;
        if (layout == Layout.TOPK_RGBA8UI && registry.highestId() > MAX_TOPK_IDS) {
            throw new IllegalArgumentException("TOPK_RGBA8UI cannot represent biome id "
                    + registry.highestId() + "; the layout stores ids in one byte (max "
                    + MAX_TOPK_IDS + "). Use ID_R32UI or split the registry.");
        }
    }

    public Layout layout() { return layout; }
    public int width() { return field.sizeCX(); }
    public int height() { return field.sizeCY(); }
    public int depth() { return field.sizeCZ(); }

    public GfxFormat idFormat() {
        return layout == Layout.ID_R32UI ? GfxFormat.R32_UINT : GfxFormat.RGBA8_UINT;
    }

    public long idBytes() {
        int per = layout == Layout.ID_R32UI ? 4 : 4;   // R32UI = 4 B, RGBA8UI = 4 B
        return (long) field.cellCapacity() * per;
    }

    /** Bytes for the TOPK weight texture, 0 for the R32UI layout. */
    public long weightBytes() {
        return layout == Layout.TOPK_RGBA8UI ? (long) field.cellCapacity() * 4 : 0;
    }

    /** Packs the whole field. {@code out} must be at least {@link #idBytes()} long. */
    public void packIds(int[] out) {
        int[] ids = field.rawIds();
        if (out.length < ids.length) {
            throw new IllegalArgumentException("output holds " + out.length + " cells, field has " + ids.length);
        }
        if (layout == Layout.ID_R32UI) {
            for (int i = 0; i < ids.length; i++) out[i] = ids[i] == BiomeField.UNKNOWN ? UNKNOWN_TEXEL : ids[i];
        } else {
            for (int i = 0; i < ids.length; i++) {
                int x = i % field.sizeCX();
                int y = (i / field.sizeCX()) % field.sizeCY();
                int z = i / (field.sizeCX() * field.sizeCY());
                BiomeSample s = sampler.sampleCellCentre(field.originCX() + x,
                        field.originCY() + y, field.originCZ() + z);
                int packed = 0;
                for (int k = 0; k < BiomeSample.MAX_ENTRIES; k++) {
                    int id = k < s.count ? s.ids[k] : UNKNOWN_TOPK;
                    if (id < 0 || id > MAX_TOPK_IDS) id = UNKNOWN_TOPK;
                    packed |= (id & 0xFF) << (k * 8);
                }
                out[i] = packed;
            }
        }
    }

    /** Packs the TOPK weights as bytes summing to 255, or fills with 0 for R32UI. */
    public void packWeights(int[] out) {
        int[] ids = field.rawIds();
        if (layout != Layout.TOPK_RGBA8UI) {
            for (int i = 0; i < ids.length; i++) out[i] = 0;
            return;
        }
        for (int i = 0; i < ids.length; i++) {
            int x = i % field.sizeCX();
            int y = (i / field.sizeCX()) % field.sizeCY();
            int z = i / (field.sizeCX() * field.sizeCY());
            BiomeSample s = sampler.sampleCellCentre(field.originCX() + x,
                    field.originCY() + y, field.originCZ() + z);
            int packed = 0;
            int assigned = 0;
            for (int k = 0; k < BiomeSample.MAX_ENTRIES; k++) {
                int q = k < s.count ? Math.round(s.weights[k] * 255f) : 0;
                if (k < s.count - 1) assigned += q;
                else if (k < s.count) q = Math.max(0, 255 - assigned);   // last one takes the remainder
                packed |= (q & 0xFF) << (k * 8);
            }
            out[i] = packed;
        }
    }

    /**
     * Clips a dirty region to the field and returns the sub-box to upload.
     * Regions entirely outside produce nothing, which is what keeps a chunk
     * load on the far side of the window from touching the GPU at all.
     */
    public List<int[]> subImageBoxes(List<DirtyRegion> regions) {
        List<int[]> out = new ArrayList<int[]>();
        for (DirtyRegion r : regions) {
            int minX = Math.max(r.minX - field.originCX(), 0);
            int minY = Math.max(r.minY - field.originCY(), 0);
            int minZ = Math.max(r.minZ - field.originCZ(), 0);
            int maxX = Math.min(r.maxX() - field.originCX(), field.sizeCX());
            int maxY = Math.min(r.maxY() - field.originCY(), field.sizeCY());
            int maxZ = Math.min(r.maxZ() - field.originCZ(), field.sizeCZ());
            if (maxX <= minX || maxY <= minY || maxZ <= minZ) continue;
            out.add(new int[] { minX, minY, minZ, maxX - minX, maxY - minY, maxZ - minZ });
        }
        return out;
    }

    /** Upload statistics for the receipts. */
    public long bytesForBoxes(List<int[]> boxes) {
        long cells = 0;
        for (int[] b : boxes) cells += (long) b[3] * b[4] * b[5];
        return cells * (layout == Layout.ID_R32UI ? 4 : 8);   // TOPK uploads ids + weights
    }

    public String receipt() {
        return "biome packer: " + layout + " " + width() + "x" + height() + "x" + depth()
                + " (" + idBytes() + " B ids" + (weightBytes() > 0 ? " + " + weightBytes() + " B weights" : "")
                + ")";
    }
}
