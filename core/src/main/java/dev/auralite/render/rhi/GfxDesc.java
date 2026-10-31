package dev.auralite.render.rhi;

import dev.auralite.render.rhi.GfxEnums.AddressMode;
import dev.auralite.render.rhi.GfxEnums.BlendFactor;
import dev.auralite.render.rhi.GfxEnums.BlendOp;
import dev.auralite.render.rhi.GfxEnums.CompareOp;
import dev.auralite.render.rhi.GfxEnums.CullMode;
import dev.auralite.render.rhi.GfxEnums.Filter;
import dev.auralite.render.rhi.GfxEnums.FrontFace;
import dev.auralite.render.rhi.GfxEnums.ImageType;
import dev.auralite.render.rhi.GfxEnums.LoadOp;
import dev.auralite.render.rhi.GfxEnums.MemoryDomain;
import dev.auralite.render.rhi.GfxEnums.MipmapMode;
import dev.auralite.render.rhi.GfxEnums.StoreOp;
import dev.auralite.render.rhi.GfxEnums.Topology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every creation descriptor in one place.
 *
 * <p>Fields are public and final-by-convention: descriptors are value objects
 * built once at pack load and hashed for pipeline-cache keys, so there is no
 * mutation and no builder ceremony. {@code hash()} is the pipeline-cache key
 * and is part of the graph's determinism gate.
 */
public final class GfxDesc {
    private GfxDesc() { }

    // ------------------------------------------------------------------ buffer
    public static final class Buffer {
        public long sizeBytes;
        public int usage;
        public MemoryDomain domain = MemoryDomain.DEVICE_LOCAL;
        public String debugName = "";

        public static Buffer of(long sizeBytes, int usage) {
            Buffer b = new Buffer();
            b.sizeBytes = sizeBytes;
            b.usage = usage;
            return b;
        }
        public Buffer named(String n) { this.debugName = n == null ? "" : n; return this; }
        public Buffer domain(MemoryDomain d) { this.domain = d; return this; }

        public long hash() {
            long h = sizeBytes * 1099511628211L;
            h ^= usage * 2654435761L;
            h ^= domain.ordinal() * 40503L;
            return h;
        }
    }

    // ------------------------------------------------------------------- image
    public static final class Image {
        public ImageType type = ImageType.TEXTURE_2D;
        public GfxFormat format = GfxFormat.RGBA8_UNORM;
        public int width = 1, height = 1, depth = 1;
        public int mipLevels = 1;
        public int arrayLayers = 1;
        public int samples = 1;
        public int usage;
        public MemoryDomain domain = MemoryDomain.DEVICE_LOCAL;
        public String debugName = "";

        public static Image tex2d(int w, int h, GfxFormat f, int usage) {
            Image i = new Image();
            i.type = ImageType.TEXTURE_2D;
            i.width = w; i.height = h; i.format = f; i.usage = usage;
            return i;
        }
        public static Image tex3d(int w, int h, int d, GfxFormat f, int usage) {
            Image i = new Image();
            i.type = ImageType.TEXTURE_3D;
            i.width = w; i.height = h; i.depth = d; i.format = f; i.usage = usage;
            return i;
        }
        public Image mips(int levels) { this.mipLevels = Math.max(1, levels); return this; }
        public Image fullMipChain() {
            int m = 1, s = Math.max(width, height);
            while (s > 1) { s >>= 1; m++; }
            this.mipLevels = m;
            return this;
        }
        public Image samples(int s) { this.samples = Math.max(1, s); return this; }
        public Image layers(int l) { this.arrayLayers = Math.max(1, l); return this; }
        public Image named(String n) { this.debugName = n == null ? "" : n; return this; }

        public long texelCount() {
            return (long) width * height * depth * arrayLayers;
        }
        /** Bytes for one mip level, level 0. Compressed formats report 0 (unknown here). */
        public long levelBytes() {
            return format.isCompressed() ? 0 : texelCount() * format.bytesPerTexel();
        }
        /** Total bytes across the whole mip chain, used by the graph's memory budget. */
        public long approxBytes() {
            if (format.isCompressed()) return 0;
            long total = 0, w = width, h = height, d = depth;
            for (int m = 0; m < mipLevels; m++) {
                total += w * h * d * arrayLayers * format.bytesPerTexel();
                w = Math.max(1, w >> 1); h = Math.max(1, h >> 1);
                if (type != ImageType.TEXTURE_3D) d = 1; else d = Math.max(1, d >> 1);
            }
            return total;
        }
        public long hash() {
            long h = 1469598103934665603L;
            h = (h ^ type.ordinal()) * 1099511628211L;
            h = (h ^ format.ordinal()) * 1099511628211L;
            h = (h ^ width) * 1099511628211L;
            h = (h ^ height) * 1099511628211L;
            h = (h ^ depth) * 1099511628211L;
            h = (h ^ mipLevels) * 1099511628211L;
            h = (h ^ arrayLayers) * 1099511628211L;
            h = (h ^ samples) * 1099511628211L;
            h = (h ^ usage) * 1099511628211L;
            return h;
        }
    }

    // ------------------------------------------------------------------ sampler
    public static final class Sampler {
        public Filter minFilter = Filter.LINEAR;
        public Filter magFilter = Filter.LINEAR;
        public MipmapMode mipmapMode = MipmapMode.LINEAR;
        public AddressMode addressU = AddressMode.CLAMP_TO_EDGE;
        public AddressMode addressV = AddressMode.CLAMP_TO_EDGE;
        public AddressMode addressW = AddressMode.CLAMP_TO_EDGE;
        public float maxAnisotropy = 1.0f;
        public boolean compareEnable = false;
        public CompareOp compareOp = CompareOp.LESS;
        public float minLod = 0.0f;
        public float maxLod = 16.0f;
        public float[] borderColor = new float[] { 0, 0, 0, 0 };
        public String debugName = "";

        public static Sampler linearClamp() { return new Sampler(); }
        public static Sampler nearestClamp() {
            Sampler s = new Sampler();
            s.minFilter = Filter.NEAREST; s.magFilter = Filter.NEAREST; s.mipmapMode = MipmapMode.NEAREST;
            return s;
        }
        public static Sampler shadowCompare() {
            Sampler s = new Sampler();
            s.compareEnable = true;
            s.minFilter = Filter.LINEAR; s.magFilter = Filter.LINEAR;
            return s;
        }
        public Sampler aniso(float a) { this.maxAnisotropy = a; return this; }
        public Sampler wrap(AddressMode m) { this.addressU = this.addressV = this.addressW = m; return this; }
        public Sampler named(String n) { this.debugName = n == null ? "" : n; return this; }

        public long hash() {
            long h = minFilter.ordinal() * 31L + magFilter.ordinal();
            h = h * 31 + mipmapMode.ordinal();
            h = h * 31 + addressU.ordinal();
            h = h * 31 + addressV.ordinal();
            h = h * 31 + addressW.ordinal();
            h = h * 31 + Float.floatToIntBits(maxAnisotropy);
            h = h * 31 + (compareEnable ? 1 : 0);
            h = h * 31 + compareOp.ordinal();
            h = h * 31 + Float.floatToIntBits(minLod);
            h = h * 31 + Float.floatToIntBits(maxLod);
            return h;
        }
    }

    // ------------------------------------------------------------- blend state
    public static final class Blend {
        public boolean enabled = false;
        public BlendFactor srcColor = BlendFactor.ONE;
        public BlendFactor dstColor = BlendFactor.ZERO;
        public BlendOp opColor = BlendOp.ADD;
        public BlendFactor srcAlpha = BlendFactor.ONE;
        public BlendFactor dstAlpha = BlendFactor.ZERO;
        public BlendOp opAlpha = BlendOp.ADD;
        public int writeMask = 0xF;

        /** Maps an OptiFine/Iris {@code blend.<program>=<src> <dst>} pair onto this state. */
        public static Blend fromLegacyPair(String srcName, String dstName) {
            Blend b = new Blend();
            b.enabled = true;
            b.srcColor = parseFactor(srcName);
            b.dstColor = parseFactor(dstName);
            b.srcAlpha = b.srcColor;
            b.dstAlpha = b.dstColor;
            return b;
        }
        public static Blend alphaBlend() {
            Blend b = new Blend();
            b.enabled = true;
            b.srcColor = BlendFactor.SRC_ALPHA;
            b.dstColor = BlendFactor.ONE_MINUS_SRC_ALPHA;
            b.srcAlpha = BlendFactor.ONE;
            b.dstAlpha = BlendFactor.ONE_MINUS_SRC_ALPHA;
            return b;
        }
        private static BlendFactor parseFactor(String n) {
            if (n == null) return BlendFactor.ONE;
            String k = n.trim().toUpperCase(java.util.Locale.ROOT);
            // Legacy GL enum spellings still floating around in old packs.
            if (k.equals("SRC_ALPHA")) return BlendFactor.SRC_ALPHA;
            if (k.equals("ONE_MINUS_SRC_ALPHA")) return BlendFactor.ONE_MINUS_SRC_ALPHA;
            if (k.equals("770")) return BlendFactor.SRC_ALPHA;
            if (k.equals("771")) return BlendFactor.ONE_MINUS_SRC_ALPHA;
            if (k.equals("768")) return BlendFactor.DST_ALPHA;
            if (k.equals("769")) return BlendFactor.ONE_MINUS_DST_ALPHA;
            if (k.equals("1")) return BlendFactor.ONE;
            if (k.equals("0")) return BlendFactor.ZERO;
            try { return BlendFactor.valueOf(k); } catch (IllegalArgumentException e) { return BlendFactor.ONE; }
        }
    }

    // --------------------------------------------------------- depth/stencil
    public static final class DepthStencil {
        public boolean depthTest = true;
        public boolean depthWrite = true;
        public CompareOp depthOp = CompareOp.LESS_OR_EQUAL;
        public boolean stencilTest = false;
        public float depthBiasConstant = 0f;
        public float depthBiasSlope = 0f;

        public static DepthStencil readOnly() {
            DepthStencil d = new DepthStencil();
            d.depthWrite = false;
            return d;
        }
        public static DepthStencil disabled() {
            DepthStencil d = new DepthStencil();
            d.depthTest = false;
            d.depthWrite = false;
            return d;
        }
    }

    // -------------------------------------------------------------- raster
    public static final class Raster {
        public CullMode cullMode = CullMode.BACK;
        public FrontFace frontFace = FrontFace.COUNTER_CLOCKWISE;
        public Topology topology = Topology.TRIANGLE_LIST;
        public boolean depthClamp = false;
        public boolean polygonOffset = false;
        public float lineWidth = 1.0f;
    }

    // --------------------------------------------------------- vertex layout
    public static final class VertexLayout {
        public static final class Attribute {
            public int location;
            public int binding;
            public GfxFormat format;
            public int offset;
            public Attribute(int location, int binding, GfxFormat format, int offset) {
                this.location = location; this.binding = binding;
                this.format = format; this.offset = offset;
            }
        }
        public static final class Binding {
            public int binding;
            public int stride;
            public boolean perInstance;
            public Binding(int binding, int stride, boolean perInstance) {
                this.binding = binding; this.stride = stride; this.perInstance = perInstance;
            }
        }
        public final List<Binding> bindings = new ArrayList<Binding>();
        public final List<Attribute> attributes = new ArrayList<Attribute>();

        public static VertexLayout empty() { return new VertexLayout(); }
        public VertexLayout binding(int index, int stride, boolean perInstance) {
            bindings.add(new Binding(index, stride, perInstance));
            return this;
        }
        public VertexLayout attribute(int location, int binding, GfxFormat format, int offset) {
            attributes.add(new Attribute(location, binding, format, offset));
            return this;
        }
        public List<Attribute> attributes() { return Collections.unmodifiableList(attributes); }
    }

    // ----------------------------------------------------------- render pass
    public static final class Attachment {
        public GfxImage image;
        public int mipLevel = 0;
        public int baseLayer = 0;
        public int layerCount = 1;
        public LoadOp loadOp = LoadOp.DONT_CARE;
        public StoreOp storeOp = StoreOp.STORE;
        public float[] clearColor = new float[] { 0, 0, 0, 0 };
        public float clearDepth = 1.0f;
        public int clearStencil = 0;
        public Blend blend = new Blend();

        public static Attachment of(GfxImage image) {
            Attachment a = new Attachment();
            a.image = image;
            return a;
        }
        public Attachment load(LoadOp op) { this.loadOp = op; return this; }
        public Attachment store(StoreOp op) { this.storeOp = op; return this; }
        public Attachment clear(float r, float g, float b, float a) {
            this.loadOp = LoadOp.CLEAR;
            this.clearColor = new float[] { r, g, b, a };
            return this;
        }
        public Attachment blend(Blend b) { this.blend = b; return this; }
    }

    public static final class RenderPass {
        public final List<Attachment> colors = new ArrayList<Attachment>();
        public Attachment depth;
        public int width, height;
        public String debugName = "";

        public static RenderPass of(int width, int height) {
            RenderPass p = new RenderPass();
            p.width = width; p.height = height;
            return p;
        }
        public RenderPass color(Attachment a) { colors.add(a); return this; }
        public RenderPass depth(Attachment a) { depth = a; return this; }
        public RenderPass named(String n) { this.debugName = n == null ? "" : n; return this; }
        public int colorCount() { return colors.size(); }
    }

    // -------------------------------------------------------------- pipelines
    public static final class GraphicsPipeline {
        public final List<GfxShaderModule> stages = new ArrayList<GfxShaderModule>();
        public VertexLayout vertexLayout = VertexLayout.empty();
        public Raster raster = new Raster();
        public DepthStencil depthStencil = new DepthStencil();
        public RenderPass renderPass;
        public PipelineLayout layout;
        public String debugName = "";

        public GraphicsPipeline stage(GfxShaderModule m) { stages.add(m); return this; }
        public GraphicsPipeline renderPass(RenderPass p) { this.renderPass = p; return this; }
        public GraphicsPipeline layout(PipelineLayout l) { this.layout = l; return this; }
        public GraphicsPipeline vertexLayout(VertexLayout v) { this.vertexLayout = v; return this; }
        public GraphicsPipeline raster(Raster r) { this.raster = r; return this; }
        public GraphicsPipeline depthStencil(DepthStencil d) { this.depthStencil = d; return this; }
        public GraphicsPipeline named(String n) { this.debugName = n == null ? "" : n; return this; }

        public boolean hasStage(GfxStage s) {
            for (GfxShaderModule m : stages) if (m.stage() == s) return true;
            return false;
        }
    }

    public static final class ComputePipeline {
        public GfxShaderModule stage;
        public PipelineLayout layout;
        public int localSizeX = 1, localSizeY = 1, localSizeZ = 1;
        public String debugName = "";

        public static ComputePipeline of(GfxShaderModule m) {
            ComputePipeline p = new ComputePipeline();
            p.stage = m;
            return p;
        }
        public ComputePipeline localSize(int x, int y, int z) {
            this.localSizeX = x; this.localSizeY = y; this.localSizeZ = z;
            return this;
        }
        public ComputePipeline layout(PipelineLayout l) { this.layout = l; return this; }
        public ComputePipeline named(String n) { this.debugName = n == null ? "" : n; return this; }
    }

    // -------------------------------------------------------- pipeline layout
    public static final class PipelineLayout {
        /** Descriptor set index -> binding descriptions, in declaration order. */
        public final List<SetLayout> sets = new ArrayList<SetLayout>();
        public int pushConstantBytes = 0;
        public GfxStage pushConstantStages = GfxStage.FRAGMENT;

        public static final class BindingDesc {
            public final int binding;
            public final Kind kind;
            public final GfxStage stages;
            public final int count;
            public BindingDesc(int binding, Kind kind, GfxStage stages, int count) {
                this.binding = binding; this.kind = kind;
                this.stages = stages; this.count = Math.max(1, count);
            }
        }
        public enum Kind {
            UNIFORM_BUFFER, STORAGE_BUFFER, COMBINED_IMAGE_SAMPLER, SAMPLED_IMAGE,
            SAMPLER, STORAGE_IMAGE, INPUT_ATTACHMENT, ACCELERATION_STRUCTURE
        }
        public static final class SetLayout {
            public final List<BindingDesc> bindings = new ArrayList<BindingDesc>();
            public SetLayout uniformBuffer(int binding, GfxStage stages) {
                bindings.add(new BindingDesc(binding, Kind.UNIFORM_BUFFER, stages, 1)); return this;
            }
            public SetLayout storageBuffer(int binding, GfxStage stages) {
                bindings.add(new BindingDesc(binding, Kind.STORAGE_BUFFER, stages, 1)); return this;
            }
            public SetLayout storageBuffer(int binding, GfxStage stages, int count) {
                bindings.add(new BindingDesc(binding, Kind.STORAGE_BUFFER, stages, count)); return this;
            }
            public SetLayout sampler(int binding, GfxStage stages) {
                bindings.add(new BindingDesc(binding, Kind.COMBINED_IMAGE_SAMPLER, stages, 1)); return this;
            }
            public SetLayout storageImage(int binding, GfxStage stages) {
                bindings.add(new BindingDesc(binding, Kind.STORAGE_IMAGE, stages, 1)); return this;
            }
        }
        public PipelineLayout set(SetLayout s) { sets.add(s); return this; }
        public PipelineLayout pushConstants(int bytes, GfxStage stages) {
            this.pushConstantBytes = bytes; this.pushConstantStages = stages; return this;
        }
        public int bindingCount() {
            int n = 0;
            for (SetLayout s : sets) n += s.bindings.size();
            return n;
        }
    }
}
