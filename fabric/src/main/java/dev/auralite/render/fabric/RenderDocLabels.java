package dev.auralite.render.fabric;

import dev.auralite.render.graph.CompiledGraph;
import dev.auralite.render.rhi.GfxCommandBuffer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * RenderDoc-friendly labelling for one frame.
 *
 * <p>The RHI side of labelling already exists: every descriptor and graph
 * pass carries a debugName, and {@link GfxCommandBuffer#writeTimestamp} is
 * documented as capture-oriented (RenderDoc reads it; the GL backend backs it
 * with its 64-query ring, Vulkan with a 256-query pool). This glue turns
 * those primitives into per-frame structure a capture can be read against:
 *
 * <ul>
 * <li>frame begin/end timestamps at fixed indices, so two captures diff by
 *     index without guessing where the frame starts;</li>
 * <li>the executed pass order recorded per compile, printed on demand
 *     ({@code -Dauralite.labelLog=true}) so a frame can be checked without a
 *     capture tool at all.</li>
 * </ul>
 *
 * <p>No claim is made that a capture has been taken: until Phase 5 runs on a
 * GPU, this class produces the labels, not receipts about them.
 */
public final class RenderDocLabels {

    private final boolean enabled;
    private List<String> passOrder = Collections.emptyList();
    private boolean announcedOrder;
    private int stamps;

    public RenderDocLabels(boolean enabled) {
        this.enabled = enabled;
    }

    public static RenderDocLabels fromProperties() {
        return new RenderDocLabels(Boolean.getBoolean("auralite.labelTimestamps"));
    }

    /** Records the pass order of a (re)compiled graph for the label log. */
    public void compiled(CompiledGraph graph) {
        passOrder = new ArrayList<String>(graph.passOrder());
        announcedOrder = false;
    }

    public void beginFrame(GfxCommandBuffer cmd) {
        if (!enabled) return;
        cmd.writeTimestamp(0);
        if (!announcedOrder && !passOrder.isEmpty()) {
            announcedOrder = true;
            if (Boolean.getBoolean("auralite.labelLog")) {
                AuraLiteRenderMod.LOGGER.info("frame pass order: {}", passOrder);
            }
        }
    }

    public void endFrame(GfxCommandBuffer cmd) {
        if (!enabled) return;
        cmd.writeTimestamp(1);
        stamps += 2;
    }

    /** Deterministic one-line receipt of what was labelled, for logs. */
    public String receipt() {
        return String.format(Locale.ROOT, "labels: enabled=%b passes=%d stamps=%d",
                enabled, passOrder.size(), stamps);
    }
}
