package dev.auralite.render.pack;

import dev.auralite.render.rhi.GfxStage;
import dev.auralite.render.rhi.ShaderSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One named program: the stages a pack supplies for it plus its fixed-function
 * state.
 *
 * <p>LEGACY mode derives the name from the filename ({@code gbuffers_terrain},
 * {@code composite3}, {@code shadow}); NATIVE mode takes it from the
 * {@code [programs]} section. Both end up here, and the graph builder is what
 * decides which pass a program runs in -- in NATIVE mode a program may run in
 * several passes, which Iris cannot express.
 */
public final class PackProgram {
    public final String name;
    private final Map<GfxStage, ShaderSource> stages = new EnumMap<GfxStage, ShaderSource>(GfxStage.class);

    public boolean enabled = true;
    public String disableReason = "";
    /** Iris {@code program.<name>.enabled=false}. */
    public boolean explicitlyDisabled;
    public String blendSrc = "";
    public String blendDst = "";
    /** Iris {@code program.<name>.composite.<setting>}. */
    public boolean shadowHardwareFiltering;
    public int blendMode = -1;
    /** Passes this program participates in; empty in LEGACY mode. */
    public String boundPass = "";
    /** Work-group size for a compute program. */
    public int localSizeX = 1, localSizeY = 1, localSizeZ = 1;
    /** Declared source paths inside the pack, in stage order. */
    public final List<String> sourcePaths = new ArrayList<String>();

    public PackProgram(String name) { this.name = name; }

    public PackProgram stage(GfxStage stage, ShaderSource source) {
        stages.put(stage, source);
        return this;
    }

    public ShaderSource stage(GfxStage stage) { return stages.get(stage); }
    public boolean hasStage(GfxStage stage) { return stages.containsKey(stage); }
    public Map<GfxStage, ShaderSource> stages() { return stages; }
    public int stageCount() { return stages.size(); }

    public boolean isCompute() { return stages.containsKey(GfxStage.COMPUTE); }

    /** True when the program has the two stages a raster pass needs. */
    public boolean isRasterComplete() {
        return stages.containsKey(GfxStage.VERTEX) && stages.containsKey(GfxStage.FRAGMENT);
    }

    public String missingStages() {
        if (isCompute()) return "";
        StringBuilder sb = new StringBuilder();
        if (!stages.containsKey(GfxStage.VERTEX)) sb.append("vertex ");
        if (!stages.containsKey(GfxStage.FRAGMENT)) sb.append("fragment ");
        return sb.toString().trim();
    }

    @Override public String toString() {
        return name + "[" + stages.keySet() + (enabled ? "" : " DISABLED:" + disableReason) + "]";
    }
}
