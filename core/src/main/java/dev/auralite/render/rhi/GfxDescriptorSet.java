package dev.auralite.render.rhi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A bound set of resources.
 *
 * <p>Deliberately backend-neutral: the GL backend turns this into indexed
 * texture/buffer-range binding calls, the Vulkan backend into a
 * {@code VkDescriptorSet} write. Packs never see the difference.
 */
public final class GfxDescriptorSet {
    public static final class Write {
        public final int binding;
        public final GfxDesc.PipelineLayout.Kind kind;
        public final GfxResource resource;
        public final long offset;
        public final long range;
        public final int arrayIndex;

        public Write(int binding, GfxDesc.PipelineLayout.Kind kind, GfxResource resource,
                     long offset, long range, int arrayIndex) {
            this.binding = binding;
            this.kind = kind;
            this.resource = resource;
            this.offset = offset;
            this.range = range;
            this.arrayIndex = arrayIndex;
        }

        public static Write uniform(int binding, GfxBuffer b) {
            return new Write(binding, GfxDesc.PipelineLayout.Kind.UNIFORM_BUFFER, b, 0, b.sizeBytes(), 0);
        }
        public static Write uniform(int binding, GfxBuffer b, long offset, long range) {
            return new Write(binding, GfxDesc.PipelineLayout.Kind.UNIFORM_BUFFER, b, offset, range, 0);
        }
        public static Write storage(int binding, GfxBuffer b) {
            return new Write(binding, GfxDesc.PipelineLayout.Kind.STORAGE_BUFFER, b, 0, b.sizeBytes(), 0);
        }
        public static Write sampler(int binding, GfxImage img, GfxSampler smp) {
            Write w = new Write(binding, GfxDesc.PipelineLayout.Kind.COMBINED_IMAGE_SAMPLER, img, 0, 0, 0);
            w.combinedSampler = smp;
            return w;
        }
        public static Write storageImage(int binding, GfxImage img) {
            return new Write(binding, GfxDesc.PipelineLayout.Kind.STORAGE_IMAGE, img, 0, 0, 0);
        }
        public GfxSampler combinedSampler;
    }

    private final int setIndex;
    private final List<Write> writes = new ArrayList<Write>();

    public GfxDescriptorSet(int setIndex) { this.setIndex = setIndex; }

    public GfxDescriptorSet add(Write w) { writes.add(w); return this; }
    public int setIndex() { return setIndex; }
    public List<Write> writes() { return Collections.unmodifiableList(writes); }
    public int size() { return writes.size(); }

    /** Validates writes against a layout; returns a named reason or {@code null}. */
    public String validateAgainst(GfxDesc.PipelineLayout layout) {
        if (layout == null) return "null pipeline layout";
        if (setIndex >= layout.sets.size()) {
            return "set " + setIndex + " not declared (layout declares " + layout.sets.size() + ")";
        }
        GfxDesc.PipelineLayout.SetLayout sl = layout.sets.get(setIndex);
        for (Write w : writes) {
            GfxDesc.PipelineLayout.BindingDesc found = null;
            for (GfxDesc.PipelineLayout.BindingDesc b : sl.bindings) {
                if (b.binding == w.binding) { found = b; break; }
            }
            if (found == null) {
                return "binding " + w.binding + " not declared in set " + setIndex;
            }
            if (found.kind != w.kind) {
                return "binding " + w.binding + " declared as " + found.kind + " but written as " + w.kind;
            }
            if (found.kind == GfxDesc.PipelineLayout.Kind.COMBINED_IMAGE_SAMPLER && w.combinedSampler == null) {
                return "binding " + w.binding + " is a combined image sampler but no sampler was supplied";
            }
        }
        return null;
    }
}
