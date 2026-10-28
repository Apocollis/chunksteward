package com.apocollis.chunksteward.mixin;

import com.apocollis.chunksteward.server.FeatureHooks;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.village.VillageCollection;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.storage.MapStorage;
import com.google.common.base.Predicate;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers {@code World} calls from a feature job (a decoration run on a terrain worker) from the job's own view of
 * the world instead of the live chunk map; see {@code FeatureScope}. Every hook is one volatile read when no job runs,
 * and a thread-local read on other threads while one does. Calls a worker must not make (entities, scheduled ticks,
 * tile entities, neighbour notification) abort the job, which then runs inline on the server thread.
 *
 * <p>Priority 500 so these run before other mods' hooks on the same methods.
 */
@Mixin(value = World.class, priority = 500)
public abstract class MixinWorldFeature {

    @Inject(method = "getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;",
            at = @At("HEAD"), cancellable = true)
    private void chunksteward$featureGetBlockState(BlockPos pos, CallbackInfoReturnable<IBlockState> cir) {
        IBlockState state = FeatureHooks.getBlockState(pos);
        if (state != null) {
            cir.setReturnValue(state);
        }
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z",
            at = @At("HEAD"), cancellable = true)
    private void chunksteward$featureSetBlockState(BlockPos pos, IBlockState state, int flags,
                                                   CallbackInfoReturnable<Boolean> cir) {
        Boolean changed = FeatureHooks.setBlockState(pos, state, flags);
        if (changed != null) {
            cir.setReturnValue(changed);
        }
    }

    @Inject(method = "getHeight(II)I", at = @At("HEAD"), cancellable = true)
    private void chunksteward$featureHeight(int x, int z, CallbackInfoReturnable<Integer> cir) {
        Integer height = FeatureHooks.height(x, z);
        if (height != null) {
            cir.setReturnValue(height);
        }
    }

    @Inject(method = "getPrecipitationHeight(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/util/math/BlockPos;",
            at = @At("HEAD"), cancellable = true)
    private void chunksteward$featurePrecipitation(BlockPos pos, CallbackInfoReturnable<BlockPos> cir) {
        BlockPos height = FeatureHooks.precipitationHeight(pos);
        if (height != null) {
            cir.setReturnValue(height);
        }
    }

    @Inject(method = "getTopSolidOrLiquidBlock(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/util/math/BlockPos;",
            at = @At("HEAD"), cancellable = true)
    private void chunksteward$featureTopSolid(BlockPos pos, CallbackInfoReturnable<BlockPos> cir) {
        BlockPos top = FeatureHooks.topSolidOrLiquid(pos);
        if (top != null) {
            cir.setReturnValue(top);
        }
    }

    @Inject(method = "canSeeSky(Lnet/minecraft/util/math/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void chunksteward$featureCanSeeSky(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Boolean sees = FeatureHooks.canSeeSky(pos);
        if (sees != null) {
            cir.setReturnValue(sees);
        }
    }

    @Inject(method = "isBlockLoaded(Lnet/minecraft/util/math/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void chunksteward$featureLoaded(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Boolean loaded = FeatureHooks.blockLoaded(pos);
        if (loaded != null) {
            cir.setReturnValue(loaded);
        }
    }

    @Inject(method = "isBlockLoaded(Lnet/minecraft/util/math/BlockPos;Z)Z", at = @At("HEAD"), cancellable = true)
    private void chunksteward$featureLoadedEmpty(BlockPos pos, boolean allowEmpty, CallbackInfoReturnable<Boolean> cir) {
        Boolean loaded = FeatureHooks.blockLoaded(pos);
        if (loaded != null) {
            cir.setReturnValue(loaded);
        }
    }

    @Inject(method = "isAreaLoaded(IIIIIIZ)Z", at = @At("HEAD"), cancellable = true)
    private void chunksteward$featureAreaLoaded(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                                boolean allowEmpty, CallbackInfoReturnable<Boolean> cir) {
        Boolean loaded = FeatureHooks.areaLoaded(minX, minZ, maxX, maxZ);
        if (loaded != null) {
            cir.setReturnValue(loaded);
        }
    }

    @Inject(method = "getChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true)
    private void chunksteward$featureChunk(int chunkX, int chunkZ, CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = FeatureHooks.chunk(chunkX, chunkZ);
        if (chunk != null) {
            cir.setReturnValue(chunk);
        }
    }

    @Inject(method = "checkLight(Lnet/minecraft/util/math/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void chunksteward$featureCheckLight(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (FeatureHooks.checkLight(pos)) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }

    @Inject(method = "getTileEntity(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/tileentity/TileEntity;",
            at = @At("HEAD"), cancellable = true)
    private void chunksteward$featureTileEntity(BlockPos pos, CallbackInfoReturnable<TileEntity> cir) {
        if (FeatureHooks.tileEntity(pos)) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "spawnEntity(Lnet/minecraft/entity/Entity;)Z", at = @At("HEAD"))
    private void chunksteward$featureSpawn(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        FeatureHooks.unsupported("spawnEntity");
    }

    @Inject(method = "scheduleUpdate(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;I)V",
            at = @At("HEAD"))
    private void chunksteward$featureSchedule(BlockPos pos, Block block, int delay, CallbackInfo ci) {
        FeatureHooks.unsupported("scheduleUpdate");
    }

    @Inject(method = "setTileEntity(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/tileentity/TileEntity;)V",
            at = @At("HEAD"))
    private void chunksteward$featureSetTileEntity(BlockPos pos, TileEntity tile, CallbackInfo ci) {
        FeatureHooks.unsupported("setTileEntity");
    }

    @Inject(method = "notifyNeighborsOfStateChange(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;Z)V",
            at = @At("HEAD"))
    private void chunksteward$featureNotify(BlockPos pos, Block block, boolean updateObservers, CallbackInfo ci) {
        FeatureHooks.unsupported("notifyNeighborsOfStateChange");
    }

    // Reads of shared state a worker cannot take a consistent copy of: the job aborts and runs inline.

    @Inject(method = "getEntitiesWithinAABB(Ljava/lang/Class;Lnet/minecraft/util/math/AxisAlignedBB;)Ljava/util/List;",
            at = @At("HEAD"))
    private void chunksteward$featureEntities(Class<?> type, AxisAlignedBB box, CallbackInfoReturnable<List<?>> cir) {
        FeatureHooks.unsupported("getEntitiesWithinAABB");
    }

    @Inject(method = "getEntitiesWithinAABB(Ljava/lang/Class;Lnet/minecraft/util/math/AxisAlignedBB;"
            + "Lcom/google/common/base/Predicate;)Ljava/util/List;", at = @At("HEAD"))
    private void chunksteward$featureEntitiesFiltered(Class<?> type, AxisAlignedBB box, Predicate<?> filter,
                                                      CallbackInfoReturnable<List<?>> cir) {
        FeatureHooks.unsupported("getEntitiesWithinAABB");
    }

    @Inject(method = "getEntitiesWithinAABBExcludingEntity(Lnet/minecraft/entity/Entity;"
            + "Lnet/minecraft/util/math/AxisAlignedBB;)Ljava/util/List;", at = @At("HEAD"))
    private void chunksteward$featureEntitiesExcluding(Entity entity, AxisAlignedBB box,
                                                       CallbackInfoReturnable<List<Entity>> cir) {
        FeatureHooks.unsupported("getEntitiesWithinAABBExcludingEntity");
    }

    @Inject(method = "getEntitiesInAABBexcluding(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/AxisAlignedBB;"
            + "Lcom/google/common/base/Predicate;)Ljava/util/List;", at = @At("HEAD"))
    private void chunksteward$featureEntitiesInAabb(Entity entity, AxisAlignedBB box, Predicate<?> filter,
                                                    CallbackInfoReturnable<List<Entity>> cir) {
        FeatureHooks.unsupported("getEntitiesInAABBexcluding");
    }

    @Inject(method = "getVillageCollection()Lnet/minecraft/village/VillageCollection;", at = @At("HEAD"))
    private void chunksteward$featureVillages(CallbackInfoReturnable<VillageCollection> cir) {
        FeatureHooks.unsupported("getVillageCollection");
    }

    @Inject(method = "getMapStorage()Lnet/minecraft/world/storage/MapStorage;", at = @At("HEAD"))
    private void chunksteward$featureMapStorage(CallbackInfoReturnable<MapStorage> cir) {
        FeatureHooks.unsupported("getMapStorage");
    }

    @Inject(method = "getPerWorldStorage()Lnet/minecraft/world/storage/MapStorage;", at = @At("HEAD"))
    private void chunksteward$featurePerWorldStorage(CallbackInfoReturnable<MapStorage> cir) {
        FeatureHooks.unsupported("getPerWorldStorage");
    }
}
