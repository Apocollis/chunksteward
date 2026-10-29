package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.config.ChunkStewardConfig;
import com.apocollis.chunksteward.mixin.AccessorChunk;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.IChunkGenerator;

/**
 * Light as a stage of the feature job, like 1.21's light status. After the decorations and the mod generators, the worker
 * computes the sky and block light of the 2x2 write window from the blocks the job leaves ({@link LightAdapter}, on the
 * {@link com.apocollis.chunksteward.light.LightEngine}). At apply, once the block writes are replayed, the server thread
 * installs the nibble arrays and the final column heights, marks the chunks light populated, and tells mods that deferred
 * light work for these chunks (Arcana Quest Tweaks' relight queue) that it is obsolete. What is left for the server thread
 * is array copies.
 *
 * <p>If the stage aborts or fails, the job's other stages are still applied and the light is made the old way. Off by
 * default ({@code features.lightStage}).
 */
final class LightStage implements FeatureStage {
    private final WorldServer world;
    private final int cx;
    private final int cz;
    private volatile LightResult result;

    LightStage(WorldServer world, int cx, int cz) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;
    }

    @Override
    public String name() {
        return "light";
    }

    @Override
    public boolean needsDecorationLock() {
        return false;
    }

    @Override
    public void run(IChunkGenerator workerGenerator) {
        final FeatureScope scope = FeatureScope.current();
        if (scope == null) {
            throw new IllegalStateException("no feature scope");
        }
        long started = System.nanoTime();
        result = LightAdapter.compute(world, new LightAdapter.Source() {
            @Override
            public IBlockState state(int x, int y, int z) {
                return scope.getBlockState(x, y, z);
            }

            @Override
            public Chunk chunk(int chunkX, int chunkZ) {
                return scope.chunk(chunkX, chunkZ);
            }
        }, cx, cz, ChunkStewardConfig.features.lightBoundaryChecks, scope);
        FeatureStats.generatorWork("light stage", System.nanoTime() - started);
    }

    /** The old way: the block writes were replayed with their light work recorded, and that runs as it always did. */
    @Override
    public void runInline() {
        FeatureStats.count(FeatureStats.LIGHT_FALLBACK);
    }

    /** Server thread, after the job's writes were replayed: installs what the worker computed. */
    void install(FeatureJob job) {
        LightResult light = result;
        if (light == null) {
            return;
        }
        int boundaryChecks = 0;
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                Chunk chunk = job.window[FeatureWindow.index(cx, cz, cx + dx, cz + dz)];
                installChunk(chunk, light, dz * 2 + dx, dx == 0 && dz == 0);
                PopulateScopes.lightInstalled(world, chunk);
            }
        }
        for (long packed : light.boundary) {
            world.checkLight(BlockPos.fromLong(packed));
            boundaryChecks++;
        }
        FeatureStats.count(FeatureStats.LIGHT_INSTALLED);
        FeatureStats.countMany(FeatureStats.LIGHT_BOUNDARY, boundaryChecks);
        result = null;
    }

    private void installChunk(Chunk chunk, LightResult light, int index, boolean center) {
        installHeights(chunk, light, index);
        installArrays(chunk, light, index);
        // The centre chunk's own populate is what this light finishes; a neighbour only when its populate has started.
        if (center || chunk.isTerrainPopulated()) {
            chunk.setLightPopulated(true);
        }
        chunk.markDirty();
    }

    /** Heights of the columns the job wrote to, exactly as the worker's height map rule left them. */
    private static void installHeights(Chunk chunk, LightResult light, int index) {
        int[] heightMap = chunk.getHeightMap();
        int[] precipitation = ((AccessorChunk) chunk).chunksteward$precipitationHeightMap();
        for (int c = 0; c < light.heightX.length; c++) {
            int x = light.heightX[c];
            int z = light.heightZ[c];
            if ((x >> 4) != chunk.x || (z >> 4) != chunk.z) {
                continue;
            }
            int column = (z & 15) << 4 | (x & 15);
            heightMap[column] = light.heightY[c];
            precipitation[column] = -999;
        }
        // The worker also computed the precipitation height of every column, so the first read (Serene Seasons reads
        // it for each column of a populated chunk) does not walk the chunk again on the server thread.
        if (light.rain != null) {
            System.arraycopy(light.rain[index], 0, precipitation, 0, 256);
        }
        int lowest = Integer.MAX_VALUE;
        for (int h : heightMap) {
            lowest = Math.min(lowest, h);
        }
        ((AccessorChunk) chunk).chunksteward$setHeightMapMinimum(lowest);
    }

    /**
     * Replaces the sections' light arrays with the worker's where they differ. Returns a bit per section index of the
     * chunk's storage array whose light changed (the caller resends those to players).
     */
    static int installArrays(Chunk chunk, LightResult light, int index) {
        int changed = 0;
        ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
        for (int s = 0; s < storages.length; s++) {
            ExtendedBlockStorage storage = storages[s];
            if (storage == Chunk.NULL_BLOCK_STORAGE) {
                continue;
            }
            int section = (storage.getYLocation() - light.yLow) >> 4;
            if (section < 0 || section >= light.sections) {
                continue;
            }
            byte[] blockData = light.block[index][section];
            if (blockData != null && !java.util.Arrays.equals(blockData, storage.getBlockLight().getData())) {
                storage.setBlockLight(new NibbleArray(blockData.clone()));
                changed |= 1 << s;
            }
            byte[] skyData = light.sky[index][section];
            if (skyData != null && storage.getSkyLight() != null
                    && !java.util.Arrays.equals(skyData, storage.getSkyLight().getData())) {
                storage.setSkyLight(new NibbleArray(skyData.clone()));
                changed |= 1 << s;
            }
        }
        return changed;
    }

    static boolean wanted() {
        return ChunkStewardConfig.features.lightStage;
    }

    static void logOnce() {
        ChunkSteward.LOGGER.info("staged populate: light is computed on the terrain workers (features.lightStage)");
    }
}
