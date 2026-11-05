package dev.auralite.render.fabric;

import dev.auralite.render.biome.BiomeField;
import dev.auralite.render.biome.BiomeRegistry;
import dev.auralite.render.biome.BiomeWindowSource;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.WorldChunk;

/**
 * The live world &rarr; {@link BiomeField}: the fabric lane's half of the
 * window logic, which is the smallest half possible.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>Until now the host frame path never filled a biome field at all. The
 * reference pack declares {@code biome_field_ids}, its bake pass writes
 * {@code biome_volume} from it, and {@code terrain} reads the volume -- so on
 * the fabric lane every live frame baked an empty volume, and the widened seam
 * could not appear however correct the bake, the barriers and the format were.
 * The GPU lane hid that behind a synthetic split (swamp on one side, plains on
 * the other), which is a fixture: it proves the machinery, not the world.
 *
 * <h2>What is here and what is in core</h2>
 *
 * <p>Everything that can be wrong quietly -- the window's origin, when a
 * rebuild is due, the throttle, and what an unloaded chunk means -- lives in
 * {@link BiomeWindowSource} in core, where the host suite drives it without a
 * game. What is left here is one function: the biome id at a block, read from
 * a loaded chunk. That is also the only part a game-less test would have to
 * fake, which is why it is the only part here.
 *
 * <p>Sampling is one probe per <b>cell</b> at the cell's centre, not one per
 * block: a cell is 4&times;4&times;4 blocks and the field stores one id per
 * cell, so reading every block would cost 64&times; the lookups for a value
 * that is discarded. The centre is the sample {@code BiomeSampler} calls the
 * cell's own, so the field and the sampler agree about which block a cell is.
 */
final class WorldBiomeSource {

    /** Cell counts the reference pack's `biome-volume` declaration is sized for. */
    static final int CELLS_X = 23;
    static final int CELLS_Y = 97;
    static final int CELLS_Z = 23;

    private final BiomeRegistry registry;
    private final BiomeWindowSource window;

    WorldBiomeSource(BiomeRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("a biome source needs a registry");
        this.registry = registry;
        BiomeField field = new BiomeField(BiomeField.DEFAULT_CELL_SIZE);
        field.allocate(CELLS_X, CELLS_Y, CELLS_Z);
        this.window = new BiomeWindowSource(field, new LiveSampler(), CELLS_X, CELLS_Y, CELLS_Z);
    }

    BiomeField field() { return window.field(); }
    BiomeRegistry registry() { return registry; }
    BiomeWindowSource window() { return window; }
    long builds() { return window.builds(); }

    /** Rebuilds from the live world when the position left the window and the throttle allows. */
    int updateFrom(World world, double playerX, double playerY, double playerZ, long frameIndex) {
        if (world == null) return 0;
        sampler.world = world;
        try {
            return window.updateFrom(playerX, playerY, playerZ, frameIndex);
        } finally {
            sampler.world = null;
        }
    }

    String receipt() { return window.receipt(); }

    /**
     * The one live call: a block's biome, resolved to this pack registry's id.
     *
     * <p>Kept as an instance so the world can be handed in per rebuild rather
     * than captured; a field that keeps the {@code World} alive across frames
     * would hold a world object past its unload, which is the kind of leak
     * that only shows up after a dimension change.
     */
    private final class LiveSampler implements BiomeWindowSource.BlockSampler {
        World world;

        /**
         * One chunk column, read whole.
         *
         * <p>The per-block path below asks the chunk cache 51 313 times for a
         * window, and every one of those calls is allowed to LOAD a chunk - so
         * a window that reaches past the player's view generates terrain on the
         * render thread instead of reporting a hole. This path asks once per
         * column (23x23 = 529 times), reads one chunk, and refuses a chunk that
         * is not loaded yet: {@code chunk.getBiome} for every block in it then
         * costs a lookup and no generation at all.
         *
         * <p>Refusing is the honest answer here and the field already counts
         * it: an unloaded column is a hole in the seam, and by the time the
         * player walks into it the window will have moved and it will be
         * loaded. Filling that hole from the player's own biome would be
         * inventing terrain.
         */
        @Override public boolean hasColumns() { return true; }

        @Override public int[] column(int chunkX, int chunkZ, int cellSize, int cellY) {
            if (world == null) return null;
            // Look at the chunk the world ALREADY has, and only if it is there.
            // `ClientWorld.getChunk(x, z)` answers the full chunk, or null when
            // it is not loaded, and it is allowed to load - so the map is
            // consulted first, which is what keeps generation off this thread.
            // `getChunkManager()` is annotated @VisibleForTesting and returns
            // @Nullable: if a Minecraft update removes it, this method must
            // answer "no column" rather than take the frame down.
            net.minecraft.client.world.ClientChunkManager manager;
            try {
                manager = world instanceof net.minecraft.client.world.ClientWorld
                        ? ((net.minecraft.client.world.ClientWorld) world).getChunkManager() : null;
            } catch (RuntimeException | LinkageError e) {
                return null;
            }
            if (manager == null) return null;
            WorldChunk chunk = manager.getWorldChunk(chunkX, chunkZ, false);
            if (chunk == null) return null;
            // The overworld's full height (1.18+: y -64..319). The NETHER and
            // the END are shorter, and a column that does not reach a window
            // cell answers UNLOADED for it - see BiomeWindowSource.cellYInColumn.
            int[] out = new int[384];
            int bottom = dev.auralite.render.biome.BiomeField.MIN_BLOCK_Y;
            int half = cellSize / 2;
            int bx = chunkX * 16 + half;
            int bz = chunkZ * 16 + half;
            for (int i = 0; i < out.length; i++) {
                out[i] = idOf(chunk.getBiome(new BlockPos(bx, bottom + i, bz)));
            }
            return out;
        }

        @Override public int idAt(int blockX, int blockY, int blockZ) {
            if (world == null) return BiomeWindowSource.UNLOADED;
            WorldChunk chunk;
            try {
                chunk = world.getChunk(blockX >> 4, blockZ >> 4);
            } catch (RuntimeException e) {
                // Past the world border, or being generated on another thread:
                // refusing to answer is the correct answer.
                return BiomeWindowSource.UNLOADED;
            }
            if (chunk == null) return BiomeWindowSource.UNLOADED;
            return idOf(chunk.getBiome(new BlockPos(blockX, blockY, blockZ)));
        }

        private int idOf(RegistryEntry<Biome> entry) {
            if (entry == null) return BiomeWindowSource.UNLOADED;
            RegistryKey<Biome> key = entry.getKey().orElse(null);
            if (key == null) return BiomeWindowSource.UNLOADED;
            dev.auralite.render.biome.BiomeRegistry.BiomeRecord record =
                    registry.byKey(key.getValue().toString());
            // A modded biome at load time: an honest hole with its own count,
            // never a guessed id that would colour a swamp as a plains.
            return record == null ? -1 : record.id;
        }
    }
}
