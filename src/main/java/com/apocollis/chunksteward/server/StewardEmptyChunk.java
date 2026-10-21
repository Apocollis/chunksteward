package com.apocollis.chunksteward.server;

import com.google.common.base.Predicate;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Random;

/**
 * An empty, throwaway chunk for the random-tick guard: reads see air, writes and entities are dropped, it never saves.
 * Vanilla's {@code EmptyChunk} does the same job but is client-only and does not exist on a dedicated server, so the
 * guard cannot use it there.
 */
public final class StewardEmptyChunk extends Chunk {
    public StewardEmptyChunk(World world, int x, int z) {
        super(world, x, z);
    }

    @Override
    public boolean isAtLocation(int x, int z) {
        return x == this.x && z == this.z;
    }

    @Override
    public int getHeightValue(int x, int z) {
        return 0;
    }

    @Override
    public void generateHeightMap() {
    }

    @Override
    public void generateSkylightMap() {
    }

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        return Blocks.AIR.getDefaultState();
    }

    @Override
    public int getBlockLightOpacity(BlockPos pos) {
        return 255;
    }

    @Override
    public int getLightFor(EnumSkyBlock type, BlockPos pos) {
        return type.defaultLightValue;
    }

    @Override
    public void setLightFor(EnumSkyBlock type, BlockPos pos, int value) {
    }

    @Override
    public int getLightSubtracted(BlockPos pos, int amount) {
        return 0;
    }

    @Override
    public void addEntity(Entity entity) {
    }

    @Override
    public void removeEntity(Entity entity) {
    }

    @Override
    public void removeEntityAtIndex(Entity entity, int index) {
    }

    @Override
    public boolean canSeeSky(BlockPos pos) {
        return false;
    }

    @Nullable
    @Override
    public TileEntity getTileEntity(BlockPos pos, Chunk.EnumCreateEntityType type) {
        return null;
    }

    @Override
    public void addTileEntity(TileEntity tileEntity) {
    }

    @Override
    public void addTileEntity(BlockPos pos, TileEntity tileEntity) {
    }

    @Override
    public void removeTileEntity(BlockPos pos) {
    }

    @Override
    public void onLoad() {
    }

    @Override
    public void onUnload() {
    }

    @Override
    public void markDirty() {
    }

    @Override
    public void getEntitiesWithinAABBForEntity(@Nullable Entity entity, AxisAlignedBB aabb, List<Entity> list,
                                               Predicate<? super Entity> filter) {
    }

    @Override
    public <T extends Entity> void getEntitiesOfTypeWithinAABB(Class<? extends T> type, AxisAlignedBB aabb,
                                                               List<T> list, Predicate<? super T> filter) {
    }

    @Override
    public boolean needsSaving(boolean flag) {
        return false;
    }

    @Override
    public Random getRandomWithSeed(long seed) {
        return new Random(getWorld().getSeed() + (long) (this.x * this.x * 4987142) + (long) (this.x * 5947611)
                + (long) (this.z * this.z) * 4392871L + (long) (this.z * 389711) ^ seed);
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public boolean isEmptyBetween(int startY, int endY) {
        return true;
    }
}
