package dev.auralite.render.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * Compile receipts.
 *
 * <p>Every number here is produced by the compiler, not estimated, and the test
 * gates assert on the receipts. {@link #receipt()} renders them as one line per
 * fact so a regression shows up as a diff in CI output rather than a red bar.
 */
public final class GraphStats {
    public int declaredPasses;
    public int enabledPasses;
    public int culledPasses;
    public int orderedPasses;
    public int rasterPasses;
    public int computePasses;
    public int copyPasses;
    public int declaredResources;
    public int importedResources;
    public int transientResources;
    public long ownedTextureBytes;
    public long importedTextureBytes;
    public long bufferBytes;
    public long aliasSavingsBytes;
    public int aliasGroups;
    public int barriers;
    public int noopBarriersSuppressed;
    public long edgeCount;
    public long compileMicros;
    public final List<String> culled = new ArrayList<String>();
    public final List<String> disabled = new ArrayList<String>();
    public final List<String> warnings = new ArrayList<String>();

    public String receipt() {
        StringBuilder sb = new StringBuilder();
        sb.append("passes: ").append(declaredPasses).append(" declared, ")
          .append(enabledPasses).append(" enabled, ")
          .append(orderedPasses).append(" ordered, ")
          .append(culledPasses).append(" culled\n");
        sb.append("  kinds: ").append(rasterPasses).append(" raster, ")
          .append(computePasses).append(" compute, ")
          .append(copyPasses).append(" copy\n");
        sb.append("resources: ").append(declaredResources).append(" declared, ")
          .append(importedResources).append(" imported, ")
          .append(transientResources).append(" transient\n");
        sb.append("memory: ").append(ownedTextureBytes).append(" B owned textures, ")
          .append(importedTextureBytes).append(" B imported, ")
          .append(bufferBytes).append(" B buffers\n");
        sb.append("aliasing: ").append(aliasGroups).append(" groups, ")
          .append(aliasSavingsBytes).append(" B reclaimable\n");
        sb.append("barriers: ").append(barriers).append(" emitted, ")
          .append(noopBarriersSuppressed).append(" suppressed as no-ops\n");
        sb.append("edges: ").append(edgeCount).append('\n');
        for (String c : culled) sb.append("  culled: ").append(c).append('\n');
        for (String d : disabled) sb.append("  disabled: ").append(d).append('\n');
        for (String w : warnings) sb.append("  warning: ").append(w).append('\n');
        return sb.toString();
    }
}
