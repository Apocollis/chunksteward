package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import com.apocollis.chunksteward.config.ChunkStewardConfig;
import com.apocollis.chunksteward.mixin.ChunkChanges;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Light audit of chunks that are already loaded ({@code /chunksteward light fix}): a terrain worker computes the light of
 * a chunk's 2x2 window with the {@link com.apocollis.chunksteward.light.LightEngine} and the server thread installs it
 * where it differs from what the chunk holds, resending the changed sections to players who have the chunk.
 *
 * <p>Unlike a feature job the chunks are live, so the work is optimistic: the server records each of the 4x4 window's
 * chunks' block-change counter when it queues the job, and installs only if every counter is the same when the worker
 * is done and every chunk is still the loaded one. A chunk that changed meanwhile is dropped and counted, and can be
 * audited again. Workers take an audit only when no terrain job or staged job is waiting.
 */
final class LightAudit {
    private static final Object LOCK = new Object();
    private static final ArrayDeque<Job> QUEUE = new ArrayDeque<Job>();
    private static final ArrayDeque<Job> FINISHED = new ArrayDeque<Job>();
    static final AtomicInteger QUEUED = new AtomicInteger();

    private LightAudit() {
    }

    static final class Job {
        final WorldServer world;
        final int cx;
        final int cz;
        final Chunk[] window = new Chunk[FeatureWindow.readCount()];
        final int[] changes = new int[FeatureWindow.readCount()];
        volatile LightResult result;
        volatile boolean failed;

        Job(WorldServer world, int cx, int cz) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
        }
    }

    /** Server thread: queues the 2x2 windows (one per chunk pair) within {@code radius} chunks of the entity. */
    static int submit(WorldServer world, Entity near, int radius) {
        ChunkProviderServer provider = world.getChunkProvider();
        int px = near == null ? 0 : ((int) Math.floor(near.posX)) >> 4;
        int pz = near == null ? 0 : ((int) Math.floor(near.posZ)) >> 4;
        int queued = 0;
        int startX = (px - radius) & ~1;
        int startZ = (pz - radius) & ~1;
        for (int cx = startX; cx <= px + radius; cx += 2) {
            for (int cz = startZ; cz <= pz + radius; cz += 2) {
                Job job = new Job(world, cx, cz);
                if (!capture(provider, job)) {
                    continue;
                }
                synchronized (LOCK) {
                    QUEUE.addLast(job);
                }
                queued++;
            }
        }
        QUEUED.addAndGet(queued);
        TerrainPipeline.wakeWorkers();
        return queued;
    }

    private static boolean capture(ChunkProviderServer provider, Job job) {
        int dimension = job.world.provider.getDimension();
        for (int dx = FeatureWindow.READ_LOW; dx <= FeatureWindow.READ_HIGH; dx++) {
            for (int dz = FeatureWindow.READ_LOW; dz <= FeatureWindow.READ_HIGH; dz++) {
                Chunk chunk = provider.getLoadedChunk(job.cx + dx, job.cz + dz);
                if (chunk == null || !chunk.isTerrainPopulated() || !chunk.isLightPopulated()
                        || FeatureLane.holds(dimension, chunk.x, chunk.z)) {
                    return false;
                }
                int i = FeatureWindow.index(job.cx, job.cz, chunk.x, chunk.z);
                job.window[i] = chunk;
                job.changes[i] = ((ChunkChanges) chunk).chunksteward$changes();
            }
        }
        return true;
    }

    static boolean hasQueued() {
        synchronized (LOCK) {
            return !QUEUE.isEmpty();
        }
    }

    /** Worker thread: the next audit, or null. */
    static Job takeForWorker() {
        synchronized (LOCK) {
            return QUEUE.pollFirst();
        }
    }

    /** Worker thread: computes the window's light from the live chunks. */
    static void execute(final Job job) {
        try {
            job.result = LightAdapter.compute(job.world, new LightAdapter.Source() {
                @Override
                public IBlockState state(int x, int y, int z) {
                    return job.window[FeatureWindow.index(job.cx, job.cz, x >> 4, z >> 4)].getBlockState(x, y, z);
                }

                @Override
                public Chunk chunk(int chunkX, int chunkZ) {
                    return job.window[FeatureWindow.index(job.cx, job.cz, chunkX, chunkZ)];
                }
            }, job.cx, job.cz, ChunkStewardConfig.features.lightBoundaryChecks, null);
        } catch (Throwable error) {
            job.failed = true;
        }
        synchronized (LOCK) {
            FINISHED.addLast(job);
        }
    }

    /** Server thread, tick end: applies finished audits within a small budget (at least one). */
    static void onTickEnd() {
        if (QUEUED.get() == 0) {
            return;
        }
        long deadline = System.nanoTime() + 3_000_000L;
        boolean first = true;
        while (true) {
            Job job;
            synchronized (LOCK) {
                if (FINISHED.isEmpty() || (!first && System.nanoTime() > deadline)) {
                    return;
                }
                job = FINISHED.pollFirst();
            }
            first = false;
            QUEUED.decrementAndGet();
            apply(job);
        }
    }

    private static void apply(Job job) {
        if (job.failed || job.result == null) {
            FeatureStats.count(FeatureStats.AUDIT_FAILED);
            return;
        }
        ChunkProviderServer provider = job.world.getChunkProvider();
        for (Chunk chunk : job.window) {
            int i = FeatureWindow.index(job.cx, job.cz, chunk.x, chunk.z);
            if (provider.getLoadedChunk(chunk.x, chunk.z) != chunk
                    || ((ChunkChanges) chunk).chunksteward$changes() != job.changes[i]
                    || FeatureLane.holds(job.world.provider.getDimension(), chunk.x, chunk.z)) {
                FeatureStats.count(FeatureStats.AUDIT_CHANGED);
                return;
            }
        }
        LightResult light = job.result;
        long changedChunks = 0;
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                Chunk chunk = job.window[FeatureWindow.index(job.cx, job.cz, job.cx + dx, job.cz + dz)];
                int mask = LightStage.installArrays(chunk, light, dz * 2 + dx);
                if (mask != 0) {
                    changedChunks++;
                    chunk.markDirty();
                    resend(job.world, chunk, mask);
                }
            }
        }
        if (changedChunks == 0) {
            FeatureStats.count(FeatureStats.AUDIT_CLEAN);
            return;
        }
        for (long packed : light.boundary) {
            job.world.checkLight(net.minecraft.util.math.BlockPos.fromLong(packed));
        }
        FeatureStats.count(FeatureStats.AUDIT_FIXED);
        FeatureStats.countMany(FeatureStats.AUDIT_CHUNKS, changedChunks);
    }

    /**
     * The server sends light only inside chunk data. A chunk a player already has gets its changed sections again by
     * marking 64 blocks of each changed section as changed, which makes {@code PlayerChunkMapEntry} send those sections'
     * data (a plain block-change packet carries no light). Sections below Y 0 are not resent.
     */
    private static void resend(WorldServer world, Chunk chunk, int sectionMask) {
        PlayerChunkMapEntry entry = world.getPlayerChunkMap().getEntry(chunk.x, chunk.z);
        if (entry == null || !entry.isSentToPlayers()) {
            return;
        }
        ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
        for (int s = 0; s < storages.length; s++) {
            if ((sectionMask & (1 << s)) == 0 || storages[s] == Chunk.NULL_BLOCK_STORAGE) {
                continue;
            }
            int base = storages[s].getYLocation();
            if (base < 0) {
                continue;
            }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 4; z++) {
                    entry.blockChanged(x, base, z);
                }
            }
        }
    }

    static List<String> describe() {
        List<String> lines = new ArrayList<String>();
        synchronized (LOCK) {
            lines.add("light audit: " + QUEUED.get() + " queued or running, " + QUEUE.size() + " waiting for a worker, "
                    + FINISHED.size() + " waiting to be applied");
        }
        lines.add("  fixed " + FeatureStats.total(FeatureStats.AUDIT_FIXED) + " windows (" + FeatureStats.total(FeatureStats.AUDIT_CHUNKS)
                + " chunks changed), already right " + FeatureStats.total(FeatureStats.AUDIT_CLEAN) + ", dropped because a chunk changed "
                + FeatureStats.total(FeatureStats.AUDIT_CHANGED) + ", failed " + FeatureStats.total(FeatureStats.AUDIT_FAILED));
        ChunkSteward.LOGGER.debug("light audit status requested");
        return lines;
    }
}
