package com.apocollis.chunksteward.server;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;

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

    /** A call a worker cannot make: aborts the job running on this thread, does nothing on other threads. */
    public static void unsupported(String what) {
        FeatureScope scope = FeatureScope.current();
        if (scope != null) {
            throw scope.abort("unsupported call: " + what);
        }
    }
}
