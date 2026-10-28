package com.apocollis.chunksteward.server;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /chunksteward light verify <n>}: computes the light of loaded, populated chunks with the worker's engine and
 * compares it with the light they hold, which vanilla settled. Run on the server thread (a chunk costs tens of
 * milliseconds), against chunks that are not held and whose whole 4x4 window is loaded and light populated, nearest to the
 * player first. Reports the cells that differ by kind and the first few with their block, so a difference can be read as
 * a bug in the engine, a vanilla inconsistency, or light a mod set by hand.
 */
final class LightVerify {
    private LightVerify() {
    }

    static List<String> run(WorldServer world, Entity player, int count) {
        List<String> lines = new ArrayList<String>();
        ChunkProviderServer provider = world.getChunkProvider();
        final int px = player == null ? 0 : ((int) Math.floor(player.posX)) >> 4;
        final int pz = player == null ? 0 : ((int) Math.floor(player.posZ)) >> 4;
        List<Chunk> candidates = new ArrayList<Chunk>();
        for (Chunk chunk : provider.getLoadedChunks()) {
            if (Math.abs(chunk.x - px) <= 16 && Math.abs(chunk.z - pz) <= 16) {
                candidates.add(chunk);
            }
        }
        Collections.sort(candidates, new Comparator<Chunk>() {
            @Override
            public int compare(Chunk a, Chunk b) {
                return Integer.compare(Math.abs(a.x - px) + Math.abs(a.z - pz), Math.abs(b.x - px) + Math.abs(b.z - pz));
            }
        });
        long started = System.nanoTime();
        int checked = 0;
        long cells = 0;
        long skyDiff = 0;
        long blockDiff = 0;
        long skyLower = 0;
        long blockLower = 0;
        int shown = 0;
        long blockMissed = 0;
        long blockBoth = 0;
        int chunksWithDifferences = 0;
        int examplesThisChunk;
        for (Chunk center : candidates) {
            if (checked >= count || System.nanoTime() - started > 6_000_000_000L) {
                break;
            }
            final Map<Long, Chunk> window = window(provider, center.x, center.z);
            if (window == null || !eligible(window, center.x, center.z, world)) {
                continue;
            }
            LightResult result = LightAdapter.compute(world, new LightAdapter.Source() {
                @Override
                public IBlockState state(int x, int y, int z) {
                    return window.get(key(x >> 4, z >> 4)).getBlockState(x, y, z);
                }

                @Override
                public Chunk chunk(int chunkX, int chunkZ) {
                    return window.get(key(chunkX, chunkZ));
                }
            }, center.x, center.z, 0, null);
            checked++;
            examplesThisChunk = 0;
            long diffBefore = skyDiff + blockDiff;
            for (int dx = 0; dx < 2; dx++) {
                for (int dz = 0; dz < 2; dz++) {
                    Chunk chunk = window.get(key(center.x + dx, center.z + dz));
                    int index = dz * 2 + dx;
                    for (ExtendedBlockStorage storage : chunk.getBlockStorageArray()) {
                        if (storage == Chunk.NULL_BLOCK_STORAGE) {
                            continue;
                        }
                        int section = (storage.getYLocation() - result.yLow) >> 4;
                        if (section < 0 || section >= result.sections) {
                            continue;
                        }
                        for (int kind = 0; kind < 2; kind++) {
                            byte[] mine = kind == 0 ? result.sky[index][section] : result.block[index][section];
                            net.minecraft.world.chunk.NibbleArray stored = kind == 0 ? storage.getSkyLight() : storage.getBlockLight();
                            if (mine == null || stored == null) {
                                continue;
                            }
                            byte[] theirs = stored.getData();
                            for (int n = 0; n < 4096; n++) {
                                int a = nibble(mine, n);
                                int b = nibble(theirs, n);
                                cells++;
                                if (a == b) {
                                    continue;
                                }
                                if (kind == 0) {
                                    skyDiff++;
                                    if (a < b) {
                                        skyLower++;
                                    }
                                } else {
                                    blockDiff++;
                                    if (a < b) {
                                        blockLower++;
                                    } else if (b == 0) {
                                        blockMissed++;
                                    } else {
                                        blockBoth++;
                                    }
                                }
                                if (shown < 14 && examplesThisChunk < 2) {
                                    shown++;
                                    examplesThisChunk++;
                                    int lx = n & 15;
                                    int lz = n >> 4 & 15;
                                    int ly = n >> 8;
                                    BlockPos at = new BlockPos(chunk.x * 16 + lx, storage.getYLocation() + ly, chunk.z * 16 + lz);
                                    lines.add("  differs at " + at.getX() + "," + at.getY() + "," + at.getZ() + " "
                                            + (kind == 0 ? "sky" : "block") + ": worker " + a + ", stored " + b + ", block "
                                            + chunk.getBlockState(at).getBlock().getRegistryName()
                                            + (kind == 1 ? "; " + emitterNear(window, at) : ""));
                                }
                            }
                        }
                    }
                }
            }
            if (skyDiff + blockDiff > diffBefore) {
                chunksWithDifferences++;
            }
        }
        double ms = (System.nanoTime() - started) / 1.0e6;
        lines.add(0, "light verify: " + checked + " chunks (2x2 each), " + cells + " nibbles compared in "
                + String.format("%.0f", ms) + " ms (" + (checked == 0 ? "-" : String.format("%.0f", ms / checked))
                + " ms per chunk including the compare); sky differs " + skyDiff + " (worker lower " + skyLower
                + "), block differs " + blockDiff + " (worker lower " + blockLower + ", worker lit where stored is 0 "
                + blockMissed + ", both lit " + blockBoth + "); chunks with a difference " + chunksWithDifferences);
        if (checked == 0) {
            lines.add("  no chunk near you has its whole 4x4 window loaded, populated and light populated");
        }
        return lines;
    }

    /** The brightest emitting block within 15 blocks of {@code at}: a light source that explains the worker's value. */
    private static String emitterNear(Map<Long, Chunk> window, BlockPos at) {
        int best = 0;
        String where = "no emitter within 15";
        for (int dx = -15; dx <= 15; dx++) {
            for (int dz = -15; dz <= 15; dz++) {
                Chunk chunk = window.get(key((at.getX() + dx) >> 4, (at.getZ() + dz) >> 4));
                if (chunk == null) {
                    continue;
                }
                for (int dy = -15; dy <= 15; dy++) {
                    BlockPos p = new BlockPos(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    IBlockState state = chunk.getBlockState(p);
                    int emits = state.getLightValue(chunk.getWorld(), p);
                    int reach = emits - (Math.abs(dx) + Math.abs(dy) + Math.abs(dz));
                    if (reach > best) {
                        best = reach;
                        where = "nearest source " + state.getBlock().getRegistryName() + " emits " + emits + " at "
                                + dx + "," + dy + "," + dz;
                    }
                }
            }
        }
        return where;
    }

    private static int nibble(byte[] data, int index) {
        int value = data[index >> 1];
        return (index & 1) == 0 ? value & 15 : (value >> 4) & 15;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static Map<Long, Chunk> window(ChunkProviderServer provider, int cx, int cz) {
        Map<Long, Chunk> window = new HashMap<Long, Chunk>();
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 2; dz++) {
                Chunk chunk = provider.getLoadedChunk(cx + dx, cz + dz);
                if (chunk == null) {
                    return null;
                }
                window.put(key(cx + dx, cz + dz), chunk);
            }
        }
        return window;
    }

    private static boolean eligible(Map<Long, Chunk> window, int cx, int cz, WorldServer world) {
        int dimension = world.provider.getDimension();
        for (Chunk chunk : window.values()) {
            if (!chunk.isTerrainPopulated() || !chunk.isLightPopulated() || FeatureLane.holds(dimension, chunk.x, chunk.z)) {
                return false;
            }
        }
        return true;
    }
}
