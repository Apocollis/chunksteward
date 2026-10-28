package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.light.LightEngine;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds a {@link LightEngine} box for a feature job's 2x2 write window from the blocks the job sees (the chunks plus its
 * own writes), runs it, and packs the light as nibble arrays the server thread can install without computing anything.
 *
 * <p>The box is the 32x32 columns of the write window plus a ring of one column. Ring cells are fixed at the light their
 * chunks hold now (the read window has them), so light already there keeps shining in, and light computed here that is
 * brighter or darker than the ring's neighbour cell is reported as a boundary cell for the server to check. Vertically the
 * box runs from the lowest section any window chunk has (below zero in a world extended by Depths Update) to the highest,
 * at least 256 blocks.
 */
final class LightAdapter {
    static final int SIZE = 34;

    private LightAdapter() {
    }

    /** What the box reads: the job's view of the blocks and the chunk objects. */
    interface Source {
        IBlockState state(int x, int y, int z);

        Chunk chunk(int chunkX, int chunkZ);
    }

    static LightResult compute(WorldServer world, Source source, int cx, int cz, int boundaryCap,
                               FeatureScope heights) {
        int yLow = 0;
        int yHigh = 256;
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 2; dz++) {
                Chunk chunk = source.chunk(cx + dx, cz + dz);
                for (ExtendedBlockStorage storage : chunk.getBlockStorageArray()) {
                    if (storage != Chunk.NULL_BLOCK_STORAGE) {
                        yLow = Math.min(yLow, storage.getYLocation());
                        yHigh = Math.max(yHigh, storage.getYLocation() + 16);
                    }
                }
            }
        }
        int sy = yHigh - yLow;
        boolean hasSky = world.provider.hasSkyLight();
        LightEngine engine = new LightEngine(SIZE, sy, SIZE);
        engine.skyAbove = true;
        int x0 = cx * 16 - 1;
        int z0 = cz * 16 - 1;
        IBlockState air = Blocks.AIR.getDefaultState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                boolean ring = x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
                int wx = x0 + x;
                int wz = z0 + z;
                Chunk ringChunk = ring ? source.chunk(wx >> 4, wz >> 4) : null;
                for (int y = 0; y < sy; y++) {
                    int i = engine.index(x, y, z);
                    int wy = yLow + y;
                    pos.setPos(wx, wy, wz);
                    if (ring) {
                        engine.fixed[i] = true;
                        engine.sky[i] = (byte) (hasSky ? ringChunk.getLightFor(EnumSkyBlock.SKY, pos) : 0);
                        engine.block[i] = (byte) ringChunk.getLightFor(EnumSkyBlock.BLOCK, pos);
                        continue;
                    }
                    IBlockState state = source.state(wx, wy, wz);
                    if (state == air || state.getBlock() == Blocks.AIR) {
                        continue;
                    }
                    engine.setOpacity(i, state.getLightOpacity(world, pos));
                    engine.setEmission(i, state.getLightValue(world, pos));
                }
            }
        }
        // The light the window chunks hold now, kept to find which border cells changed.
        List<int[]> borderCells = new ArrayList<int[]>();
        for (int z = 1; z < SIZE - 1; z++) {
            for (int x = 1; x < SIZE - 1; x++) {
                if (x == 1 || z == 1 || x == SIZE - 2 || z == SIZE - 2) {
                    borderCells.add(new int[] {x, z});
                }
            }
        }
        int[] before = new int[borderCells.size() * sy * 2];
        int k = 0;
        for (int[] cell : borderCells) {
            int wx = x0 + cell[0];
            int wz = z0 + cell[1];
            Chunk chunk = source.chunk(wx >> 4, wz >> 4);
            for (int y = 0; y < sy; y++) {
                pos.setPos(wx, yLow + y, wz);
                before[k++] = hasSky ? chunk.getLightFor(EnumSkyBlock.SKY, pos) : 0;
                before[k++] = chunk.getLightFor(EnumSkyBlock.BLOCK, pos);
            }
        }
        if (hasSky) {
            engine.computeSky();
        }
        engine.computeBlock();

        LightResult result = new LightResult(cx, cz, yLow, sy / 16);
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                int chunkIndex = dz * 2 + dx;
                for (int section = 0; section < result.sections; section++) {
                    byte[] skyData = new byte[2048];
                    byte[] blockData = new byte[2048];
                    for (int ly = 0; ly < 16; ly++) {
                        int y = section * 16 + ly;
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                int i = engine.index(1 + dx * 16 + lx, y, 1 + dz * 16 + lz);
                                int nibble = ly << 8 | lz << 4 | lx;
                                pack(skyData, nibble, engine.sky[i]);
                                pack(blockData, nibble, engine.block[i]);
                            }
                        }
                    }
                    result.sky[chunkIndex][section] = skyData;
                    result.block[chunkIndex][section] = blockData;
                }
            }
        }

        // Cells just outside the window next to a border cell whose light changed.
        Set<Long> boundary = new LinkedHashSet<Long>();
        k = 0;
        int[][] outward = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] cell : borderCells) {
            int x = cell[0];
            int z = cell[1];
            for (int y = 0; y < sy; y++) {
                int i = engine.index(x, y, z);
                int oldSkyValue = before[k++];
                int oldBlockValue = before[k++];
                if (oldSkyValue == engine.sky[i] && oldBlockValue == engine.block[i]) {
                    continue;
                }
                for (int[] d : outward) {
                    int nx = x + d[0];
                    int nz = z + d[1];
                    if (nx == 0 || nz == 0 || nx == SIZE - 1 || nz == SIZE - 1) {
                        if (boundary.size() < boundaryCap) {
                            boundary.add(new BlockPos(x0 + nx, yLow + y, z0 + nz).toLong());
                        }
                    }
                }
            }
        }
        result.boundary = new long[boundary.size()];
        int b = 0;
        for (Long value : boundary) {
            result.boundary[b++] = value;
        }
        if (heights != null) {
            final List<int[]> columns = new ArrayList<int[]>();
            heights.forEachHeight(new FeatureScope.HeightConsumer() {
                @Override
                public void accept(int x, int z, int height) {
                    columns.add(new int[] {x, z, height});
                }
            });
            result.heightX = new int[columns.size()];
            result.heightZ = new int[columns.size()];
            result.heightY = new int[columns.size()];
            for (int c = 0; c < columns.size(); c++) {
                result.heightX[c] = columns.get(c)[0];
                result.heightZ[c] = columns.get(c)[1];
                result.heightY[c] = columns.get(c)[2];
            }
        }
        return result;
    }

    private static void pack(byte[] data, int index, int value) {
        int at = index >> 1;
        if ((index & 1) == 0) {
            data[at] = (byte) ((data[at] & 0xF0) | (value & 15));
        } else {
            data[at] = (byte) ((data[at] & 0x0F) | ((value & 15) << 4));
        }
    }
}
