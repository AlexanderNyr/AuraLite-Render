package dev.auralite.render.pack;

import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxException;
import dev.auralite.render.rhi.GfxStage;
import dev.auralite.render.rhi.ShaderSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // ------------------------------------------------ image uniforms (the
    // pack's descriptor bridge: a declaration's name IS the graph resource
    // name; there is no other mapping table).

    /** One {@code layout(set, binding[, format]) uniform ... image/sampler name} declaration. */
    public static final class ImageDecl {
        public final int set;
        public final int binding;
        public final GfxDesc.PipelineLayout.Kind kind;
        public final String name;
        public final GfxStage stage;

        ImageDecl(int set, int binding, GfxDesc.PipelineLayout.Kind kind, String name, GfxStage stage) {
            this.set = set;
            this.binding = binding;
            this.kind = kind;
            this.name = name;
            this.stage = stage;
        }

        @Override public String toString() {
            return "set " + set + " binding " + binding + " " + kind + " '" + name + "'";
        }
    }

    private static final Pattern IMAGE_DECL = Pattern.compile(
            "layout\\s*\\(\\s*set\\s*=\\s*(\\d+)\\s*,\\s*binding\\s*=\\s*(\\d+)\\s*[^)]*\\)"
            + "\\s*uniform\\s+(?:(?:readonly|writeonly)\\s+)?"
            + "((?:u?i?image[23]D(?:Array)?)|(?:[ui]?sampler\\w+)|(?:texture\\w+))"
            + "\\s+(\\w+)\\s*;");

    private List<ImageDecl> imageDecls;

    /**
     * Image-uniform declarations across all GLSL stages, deduplicated per
     * {@code set/binding}. A binding that means two different things, or one
     * name claimed by two bindings, throws by name - two shaders disagreeing
     * must not reach a driver to be discovered.
     */
    public List<ImageDecl> imageDecls() {
        if (imageDecls != null) return imageDecls;
        List<ImageDecl> out = new ArrayList<ImageDecl>();
        Map<String, ImageDecl> byBinding = new HashMap<String, ImageDecl>();
        Map<String, String> nameOwner = new HashMap<String, String>();
        for (Map.Entry<GfxStage, ShaderSource> e : stages.entrySet()) {
            ShaderSource src = e.getValue();
            if (src == null || src.kind() != ShaderSource.Kind.GLSL) continue;
            Matcher m = IMAGE_DECL.matcher(src.text());
            while (m.find()) {
                int set = Integer.parseInt(m.group(1));
                int binding = Integer.parseInt(m.group(2));
                String type = m.group(3);
                String uniformName = m.group(4);
                GfxDesc.PipelineLayout.Kind kind = type.contains("image")
                        ? GfxDesc.PipelineLayout.Kind.STORAGE_IMAGE
                        : GfxDesc.PipelineLayout.Kind.COMBINED_IMAGE_SAMPLER;
                String bindingKey = set + "/" + binding;
                ImageDecl prev = byBinding.get(bindingKey);
                if (prev != null) {
                    if (!prev.name.equals(uniformName) || prev.kind != kind) {
                        throw new GfxException("pack program '" + name + "': binding " + bindingKey
                                + " declared as " + prev.kind + " '" + prev.name + "' in "
                                + prev.stage + " and as " + kind + " '" + uniformName + "' in "
                                + e.getKey());
                    }
                    continue;
                }
                String owner = nameOwner.get(uniformName);
                if (owner != null) {
                    throw new GfxException("pack program '" + name + "': uniform '" + uniformName
                            + "' declared at both " + owner + " and " + bindingKey
                            + " - one uniform, one graph resource");
                }
                nameOwner.put(uniformName, bindingKey);
                ImageDecl decl = new ImageDecl(set, binding, kind, uniformName, e.getKey());
                byBinding.put(bindingKey, decl);
                out.add(decl);
            }
        }
        imageDecls = out;
        return out;
    }

    /**
     * The pipeline layout these declarations describe, or {@code null} when the
     * program declares no image uniforms (providers attach it to the pipeline
     * desc; the graph body binds the sets against it).
     */
    public GfxDesc.PipelineLayout layout() {
        List<ImageDecl> decls = imageDecls();
        if (decls.isEmpty()) return null;
        int maxSet = 0;
        for (ImageDecl d : decls) {
            if (d.set > maxSet) maxSet = d.set;
        }
        GfxDesc.PipelineLayout layout = new GfxDesc.PipelineLayout();
        while (layout.sets.size() <= maxSet) {
            layout.sets.add(new GfxDesc.PipelineLayout.SetLayout());
        }
        for (ImageDecl d : decls) {
            GfxDesc.PipelineLayout.SetLayout sl = layout.sets.get(d.set);
            if (d.kind == GfxDesc.PipelineLayout.Kind.STORAGE_IMAGE) {
                sl.storageImage(d.binding, d.stage);
            } else {
                sl.sampler(d.binding, d.stage);
            }
        }
        return layout;
    }

    @Override public String toString() {
        return name + "[" + stages.keySet() + (enabled ? "" : " DISABLED:" + disableReason) + "]";
    }
}
