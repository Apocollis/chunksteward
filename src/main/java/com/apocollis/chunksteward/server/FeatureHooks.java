package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.ChunkSteward;
import net.minecraft.block.state.IBlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import java.util.function.Consumer;

/**
 * The entry points {@code MixinWorldFeature} calls. Each returns null (or false) when no feature job runs on the
 * calling thread, which tells the mixin to let the vanilla method run. Kept apart from the mixin so the mixin class
 * has no logic and the scope stays testable without Mixin.
 */
public final class FeatureHooks {
    private FeatureHooks() {
    }

    public static IBlockState getBlockState(BlockPos pos) {
        FeatureScope scope = FeatureScope.current();
        return scope == null ? null : scope.getBlockState(pos);
    }

    public static Boolean setBlockState(BlockPos pos, IBlockState state, int flags) {
        FeatureScope scope = FeatureScope.current();
        return scope == null ? null : Boolean.valueOf(scope.setBlockState(pos, state, flags));
    }

    public static Integer height(int x, int z) {
        FeatureScope scope = FeatureScope.current();
        return scope == null ? null : Integer.valueOf(scope.heightOf(x, z));
    }

    public static BlockPos precipitationHeight(BlockPos pos) {
        FeatureScope scope = FeatureScope.current();
        return scope == null ? null : scope.getPrecipitationHeight(pos);
    }

    public static BlockPos topSolidOrLiquid(BlockPos pos) {
        FeatureScope scope = FeatureScope.current();
        return scope == null ? null : scope.getTopSolidOrLiquidBlock(pos);
    }

    public static Boolean canSeeSky(BlockPos pos) {
        FeatureScope scope = FeatureScope.current();
        return scope == null ? null : Boolean.valueOf(scope.canSeeSky(pos));
    }

    public static Boolean blockLoaded(BlockPos pos) {
        FeatureScope scope = FeatureScope.current();
        return scope == null ? null : Boolean.valueOf(scope.inRead(pos.getX() >> 4, pos.getZ() >> 4));
    }

    public static Boolean areaLoaded(int minX, int minZ, int maxX, int maxZ) {
        FeatureScope scope = FeatureScope.current();
        return scope == null ? null : Boolean.valueOf(scope.areaInRead(minX, minZ, maxX, maxZ));
    }

    /** The chunk for a chunk-provider call from a feature job: inside the window, or an abort. Null on other threads. */
    public static Chunk providerChunk(int chunkX, int chunkZ) {
        FeatureScope scope = FeatureScope.current();
        return scope == null ? null : scope.chunk(chunkX, chunkZ);
    }

    public static Chunk chunk(int chunkX, int chunkZ) {
        FeatureScope scope = FeatureScope.current();
        return scope == null ? null : scope.chunk(chunkX, chunkZ);
    }

    /** True when the check was recorded for the light replay and the vanilla call must be skipped. */
    public static boolean checkLight(BlockPos pos) {
        FeatureScope scope = FeatureScope.current();
        if (scope == null) {
            return false;
        }
        scope.journal.check(FeatureJournal.pack(pos.getX(), pos.getY(), pos.getZ()));
        return true;
    }

    /** True when the job is running and the block has no tile entity, so the answer is null. Aborts otherwise. */
    public static boolean tileEntity(BlockPos pos) {
        FeatureScope scope = FeatureScope.current();
        if (scope == null) {
            return false;
        }
        IBlockState state = scope.getBlockState(pos);
        if (state.getBlock().hasTileEntity(state)) {
            throw scope.abort("tile entity read at " + pos);
        }
        return true;
    }

    /** A direct {@code Chunk.setBlockState} on a worker would write a live chunk, bypassing the journal: abort. */
    public static void chunkWrite() {
        FeatureScope scope = FeatureScope.current();
        if (scope != null) {
            throw scope.abort("direct chunk write");
        }
    }

    /**
     * {@code World.immediateBlockTick} from a feature job: runs the block's {@code updateTick} without setting
     * {@code scheduledUpdatesAreImmediate}, which is shared with the server thread and would stay set if the tick
     * aborts. Nothing is lost: {@code scheduleUpdate} and {@code updateBlockTick}, the flag's only readers, abort a
     * job before they look at it. False when no job runs on this thread, so the caller runs the vanilla sequence.
     */
    public static boolean immediateBlockTick(net.minecraft.world.World world, BlockPos pos, IBlockState state,
                                             java.util.Random rand) {
        if (FeatureScope.current() == null) {
            return false;
        }
        state.getBlock().updateTick(world, pos, state, rand);
        return true;
    }

    /** A call a worker cannot make: aborts the job running on this thread, does nothing on other threads. */
    public static void unsupported(String what) {
        FeatureScope scope = FeatureScope.current();
        if (scope != null) {
            throw scope.abort("unsupported call: " + what);
        }
    }

    // ---- PlayerChunkMap thread guard ----------------------------------------------------------------------

    /**
     * The pure decision behind the {@code PlayerChunkMap} guards; true means the caller must cancel the vanilla call.
     * A feature worker aborts its job. Any other off-thread caller is logged once per key with its stack trace (so the
     * calling mod is visible) and the call is rescheduled onto the server thread. Only reached from the slow path,
     * so it may allocate.
     */
    static boolean guardServerThread(boolean inFeatureScope, boolean isServerThread, String key,
                                     Runnable scheduleOnMain, Consumer<String> abort, Consumer<String> warn) {
        if (inFeatureScope) {
            abort.accept(key);
            return true;
        }
        if (isServerThread) {
            return false;
        }
        if (FeatureStats.firstSeen("offthread:" + key)) {
            warn.accept(key);
        }
        scheduleOnMain.run();
        return true;
    }

    private static void warnOffThread(String key) {
        ChunkSteward.LOGGER.warn("{} called off the server thread (thread {}); rescheduled onto it",
                key, Thread.currentThread().getName(), new Throwable("off-thread " + key));
    }

    /** Cheap server-thread test; treats a map with no world or server (tests) as the server thread. */
    private static boolean onServerThread(PlayerChunkMap map) {
        WorldServer world = map.getWorldServer();
        MinecraftServer server = world == null ? null : world.getMinecraftServer();
        return server == null || server.isCallingFromMinecraftThread();
    }

    private static void runOnServer(PlayerChunkMap map, Runnable task) {
        WorldServer world = map.getWorldServer();
        if (world != null) {
            world.addScheduledTask(task);
        }
    }

    /** A rescheduled call must not touch an entry the map dropped in the meantime. */
    private static boolean stillPresent(PlayerChunkMap map, PlayerChunkMapEntry entry) {
        ChunkPos at = entry.getPos();
        return map.getEntry(at.x, at.z) == entry;
    }

    /** True when the vanilla {@code markBlockForUpdate} must be skipped. No allocation on the server thread. */
    public static boolean guardMarkBlock(final PlayerChunkMap map, BlockPos pos) {
        boolean server = onServerThread(map);
        boolean scope = FeatureScope.current() != null;
        if (server && !scope) {
            return false;
        }
        final BlockPos at = pos.toImmutable();
        return guardServerThread(scope, server, "PlayerChunkMap.markBlockForUpdate", new Runnable() {
            @Override
            public void run() {
                runOnServer(map, new Runnable() {
                    @Override
                    public void run() {
                        map.markBlockForUpdate(at);
                    }
                });
            }
        }, FeatureHooks::unsupported, FeatureHooks::warnOffThread);
    }

    /** True when the vanilla {@code entryChanged} must be skipped. No allocation on the server thread. */
    public static boolean guardEntryChanged(final PlayerChunkMap map, final PlayerChunkMapEntry entry) {
        boolean server = onServerThread(map);
        boolean scope = FeatureScope.current() != null;
        if (server && !scope) {
            return false;
        }
        return guardServerThread(scope, server, "PlayerChunkMap.entryChanged", new Runnable() {
            @Override
            public void run() {
                runOnServer(map, new Runnable() {
                    @Override
                    public void run() {
                        if (stillPresent(map, entry)) {
                            map.entryChanged(entry);
                        }
                    }
                });
            }
        }, FeatureHooks::unsupported, FeatureHooks::warnOffThread);
    }

    /** True when the vanilla {@code blockChanged} must be skipped. No allocation on the server thread. */
    public static boolean guardBlockChanged(final PlayerChunkMap map, final PlayerChunkMapEntry entry,
                                            final int x, final int y, final int z) {
        boolean server = onServerThread(map);
        boolean scope = FeatureScope.current() != null;
        if (server && !scope) {
            return false;
        }
        return guardServerThread(scope, server, "PlayerChunkMapEntry.blockChanged", new Runnable() {
            @Override
            public void run() {
                runOnServer(map, new Runnable() {
                    @Override
                    public void run() {
                        if (stillPresent(map, entry)) {
                            entry.blockChanged(x, y, z);
                        }
                    }
                });
            }
        }, FeatureHooks::unsupported, FeatureHooks::warnOffThread);
    }
}
