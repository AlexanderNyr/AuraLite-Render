package dev.auralite.render.biome;

import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxDevice;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxImage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Puts a baked {@link BiomeSeamVolume} into the image a pass reads -- the half
 * of the widened seam that lived nowhere until r61.
 *
 * <h2>The hole this fills</h2>
 *
 * <p>{@code CompiledGraph.allocate()} creates every owned image and never
 * writes one byte of it, and the fabric host bound the volume to the frame
 * without ever uploading it. On a GPU that means the {@code biome_field}
 * compute pass read <em>uninitialised device memory</em>: not "no seam", but a
 * seam made of whatever the allocator last left there -- random ids, a picture
 * that changes between runs, and a freshness gate that reported
 * {@code fresh=true} the whole time because it only ever compared two CPU
 * integers. A green gate over uninitialised memory is the worst kind of green.
 *
 * <h2>What freshness means once an upload exists</h2>
 *
 * <p>{@link BiomeSeamFreshness#markBaked()} is CPU bookkeeping: it says the
 * arrays were rebuilt from the field's current version. It says nothing about
 * whether the GPU holds those bytes. So this class does not touch the record
 * the bake wrote. It keeps an {@link UploadState} of its own -- which field
 * version it last successfully pushed, whether the push completed, and how many
 * bytes moved -- and refuses to answer {@code uploaded} for a version it did
 * not push.
 *
 * <h2>Two formats, one byte layout each</h2>
 *
 * <ul>
 *   <li>{@code r32_uint} (the reference pack's {@code biome_field_ids}): one
 *       {@code uint} per cell, the seam's dominant id.</li>
 *   <li>{@code r32g32_uint} (the pack's {@code biome_volume} layout): the four
 *       top ids and their weights packed the way
 *       {@link BiomeSeamVolume#ids()} already holds them, for a pack that reads
 *       the volume imported instead of running the compute bake.</li>
 * </ul>
 *
 * <p>The data goes through the device's own {@code uploadImage} path rather
 * than a bespoke staging buffer, so the backend keeps whatever upload policy it
 * already has and the layout decision stays in one place. That policy is the
 * one BIOME_PLAN step 9 still owes in writing.
 */
public final class BiomeVolumeUpload {

    /** What a caller is told about the GPU's copy. Honest by construction. */
    public static final class UploadState {
        private final long pushedVersion;
        private final boolean pushed;
        private final long bytes;
        private final long nanos;
        private final String format;

        UploadState(long pushedVersion, boolean pushed, long bytes, long nanos, String format) {
            this.pushedVersion = pushedVersion;
            this.pushed = pushed;
            this.bytes = bytes;
            this.nanos = nanos;
            this.format = format;
        }

        /** True only when the bytes now on the GPU came from the field version named. */
        public boolean isUploaded() { return pushed; }

        /** The field version the GPU holds, or {@code Long.MIN_VALUE} when it holds none. */
        public long pushedVersion() { return pushedVersion; }

        public long bytes() { return bytes; }
        public long nanos() { return nanos; }

        /** The destination layout the bytes were packed for, or {@code "none"}. */
        public String format() { return format; }

        /**
         * The GPU-holds question, asked by a host before it lets a frame read
         * the volume. A push that never happened (no image yet, a device lost)
         * is a refusal, not a warning.
         */
        public void checkUploaded(String consumer) {
            if (!pushed) {
                throw new IllegalStateException("BIOME_VOLUME_NOT_UPLOADED: '" + consumer
                        + "' is about to read a seam the device was never given"
                        + " (host holds " + (pushedVersion == Long.MIN_VALUE
                                ? "nothing" : "v" + pushedVersion) + ")");
            }
        }

        /**
         * The bytes here are the bytes that MOVED - the packed buffer's own
         * capacity - and the layout is named next to them. A host that prints
         * cells x 4 instead (r62 did, and called 205 252 of r32_uint "401 764
         * B packed") states a number no buffer ever held.
         */
        public String receipt() {
            return "upload=" + (pushed ? "v" + pushedVersion : "none")
                    + " bytes=" + bytes + " format=" + format + " nanos=" + nanos;
        }
    }

    private static final UploadState NONE = new UploadState(Long.MIN_VALUE, false, 0L, 0L, "none");

    private UploadState state = NONE;
    private long pushes;
    private long skips;

    public long pushes() { return pushes; }
    public long skips() { return skips; }
    public UploadState state() { return state; }

    /** True when the GPU copy matches the field state the bake saw. */
    public boolean isCurrentFor(BiomeSeamVolume volume) {
        return state.isUploaded()
                && state.pushedVersion() == volume.field().version()
                && volume.freshness().isFresh();
    }

    /**
     * Uploads the volume to {@code dst} when the arrays it holds differ from
     * what was pushed last, and reports what happened.
     *
     * <p>{@code dst} may be null while a graph is not allocated yet; that is
     * recorded as "not uploaded", never as success. The same is true of the
     * skip case: an unchanged bake is not re-pushed, and the skip is counted so
     * a receipt can show the pacing works.
     *
     * @param volume the bake to push
     * @param dst    the image the pack's pass samples, or null
     * @param dev    the device that owns {@code dst}, or null
     * @param force  push even when the field version is unchanged (a new image,
     *               a rebuilt device)
     */
    public UploadState upload(BiomeSeamVolume volume, GfxImage dst, GfxDevice dev, boolean force) {
        if (volume == null) throw new IllegalArgumentException("nothing to upload");
        if (dst == null || dev == null) {
            state = new UploadState(volume.field().version(), false, 0L, 0L, "no image");
            return state;
        }
        long version = volume.field().version();
        if (!force && state.isUploaded() && state.pushedVersion() == version) {
            skips++;
            return state;
        }
        GfxDesc.Image d = dst.desc();
        int sx = volume.sizeX(), sy = volume.sizeY(), sz = volume.sizeZ();
        if (d.width != sx || d.height != sy || d.depth != sz) {
            throw new IllegalStateException("BIOME_VOLUME_EXTENT: the image is "
                    + d.width + "x" + d.height + "x" + d.depth + " but the bake is "
                    + sx + "x" + sy + "x" + sz
                    + "; the pack's declared extent and the host's window must be one number");
        }
        ByteBuffer buf = pack(volume, d.format);
        long t0 = System.nanoTime();
        dev.uploadImage(dst, 0, buf);
        long nanos = System.nanoTime() - t0;
        pushes++;
        state = new UploadState(version, true, buf.capacity(), nanos, d.format.name().toLowerCase());
        return state;
    }

    /**
     * How many bytes {@link #pack} would move for this volume and layout. The
     * r32_uint reference-pack upload is ONE int per cell, so it is half the
     * r32g32_uint figure the volume's own size suggests - a receipt that
     * multiplies cells by 4 and calls it "packed" is quoting the other format.
     */
    public static long packedBytes(BiomeSeamVolume volume, GfxFormat format) {
        if (volume == null) throw new IllegalArgumentException("nothing to size");
        long cells = (long) volume.sizeX() * volume.sizeY() * volume.sizeZ();
        return cells * 4L * (format == GfxFormat.R32G32_UINT ? 2 : 1);
    }

    /** The bytes for a destination format, one cell after another, x fastest. */
    public static ByteBuffer pack(BiomeSeamVolume volume, GfxFormat format) {
        if (volume == null) throw new IllegalArgumentException("nothing to pack");
        int sx = volume.sizeX(), sy = volume.sizeY(), sz = volume.sizeZ();
        int cells = sx * sy * sz;
        boolean pair = format == GfxFormat.R32G32_UINT;
        boolean single = format == GfxFormat.R32_UINT;
        if (!pair && !single) {
            throw new IllegalStateException("BIOME_VOLUME_FORMAT: a seam upload needs r32_uint or "
                    + "r32g32_uint, not " + format);
        }
        int ints = pair ? cells * 2 : cells;
        ByteBuffer buf = ByteBuffer.allocateDirect(ints * 4).order(ByteOrder.nativeOrder());
        int[] ids = volume.ids();
        for (int i = 0; i < cells; i++) {
            if (pair) {
                // The volume layout the pack declares: two uints per cell holding
                // the top four ids and their quantised weights, exactly as the
                // bake left them (four bytes packed, low slot first).
                buf.putInt(ids[i]);
                buf.putInt(volume.weights()[i]);
            } else {
                // One id per cell: the dominant id, which is the first slot of
                // the packed word -- what the reference pack's biome_field pass
                // re-widens on the GPU.
                buf.putInt(ids[i] & 0xFF);
            }
        }
        buf.flip();
        return buf;
    }
}
