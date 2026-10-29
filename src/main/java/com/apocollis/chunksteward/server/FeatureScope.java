package com.apocollis.chunksteward.server;

import com.apocollis.chunksteward.config.ChunkStewardConfig;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * The world a feature job sees. While a terrain worker runs a job, {@code World} calls made on that thread
 * (see {@code MixinWorldFeature}) are answered here instead of from the live chunk provider:
 * <ul>
 * <li>reads come from the job's own writes first, then from the chunk objects captured when the job was accepted
 * (the 4x4 read window), never from the shared chunk map;</li>
 * <li>writes go to a {@link FeatureJournal}; the loaded chunks are not touched, and the server thread replays the
 * journal when the job is promoted;</li>
 * <li>heights, precipitation heights and sky checks are worked out from the same view, with the vanilla height-map
 * rule ({@link ColumnHeights}), so a decoration placed after a tree sees the tree;</li>
 * <li>anything a worker cannot do safely aborts the job (see {@link FeatureAbort}); the server thread then runs the
 * decoration inline.</li>
 * </ul>
 *
 * <p>Threads other than the job's own are untouched: the scope is thread-bound and the hooks cost one volatile read
 * while no job runs.
 */
final class FeatureScope {
    /** Number of scopes begun and not ended; the hooks read it before touching the thread-local. */
    private static volatile int active;
    private static final ThreadLocal<FeatureScope> CURRENT = new ThreadLocal<FeatureScope>();

    final World world;
    final int cx;
    final int cz;
    final FeatureJournal<IBlockState> journal = new FeatureJournal<IBlockState>();

    private final Chunk[] window;
    /** Column height after the job's writes, keyed by {@link FeatureJournal#pack(int, int, int)} with y 0. */
    private final FeatureJournal.LongMap<Integer> heights = new FeatureJournal.LongMap<Integer>();
    /** Highest y the job wrote in a column, to start scans above new sections. */
    private final FeatureJournal.LongMap<Integer> writtenTop = new FeatureJournal.LongMap<Integer>();
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos readPos = new BlockPos.MutableBlockPos();
    private volatile String abortReason;
    private volatile String culprit;
    private volatile String culpritGenerator;
    long reads;

    FeatureScope(World world, int cx, int cz, Chunk[] window) {
        if (window.length != FeatureWindow.readCount()) {
            throw new IllegalArgumentException("window must hold " + FeatureWindow.readCount() + " chunks");
        }
        this.world = world;
        this.cx = cx;
        this.cz = cz;
        this.window = window;
    }

    /** The scope of the job running on this thread, or null. */
    static FeatureScope current() {
        return active > 0 ? CURRENT.get() : null;
    }

    void begin() {
        CURRENT.set(this);
        synchronized (FeatureScope.class) {
            active++;
        }
    }

    void end() {
        CURRENT.remove();
        synchronized (FeatureScope.class) {
            active--;
        }
    }

    String abortReason() {
        return abortReason;
    }

    /** The mod class behind the first abort: the generator that was running, else the first mod frame on the stack. */
    String culprit() {
        return culprit;
    }

    /** Class name of the mod generator that was running when the job aborted, or null. */
    String culpritGenerator() {
        return culpritGenerator;
    }

    FeatureAbort abort(String reason) {
        if (abortReason == null) {
            Class<?> generator = GeneratorPlan.running();
            culpritGenerator = generator == null ? null : generator.getName();
            culprit = culpritGenerator != null ? culpritGenerator
                    : FeatureStats.culpritOf(new Throwable().getStackTrace());
            abortReason = reason;
        }
        return new FeatureAbort(reason);
    }

    // ---- chunks -------------------------------------------------------------------------------------------

    boolean inRead(int chunkX, int chunkZ) {
        return FeatureWindow.inRead(cx, cz, chunkX, chunkZ);
    }

    /** The captured chunk, or an abort when it is outside the read window. */
    Chunk chunk(int chunkX, int chunkZ) {
        if (!FeatureWindow.inRead(cx, cz, chunkX, chunkZ)) {
            throw abort("read outside the window at chunk " + chunkX + "," + chunkZ);
        }
        return window[FeatureWindow.index(cx, cz, chunkX, chunkZ)];
    }

    /** True when every chunk of the block box is in the read window. */
    boolean areaInRead(int minX, int minZ, int maxX, int maxZ) {
        return inRead(minX >> 4, minZ >> 4) && inRead(maxX >> 4, maxZ >> 4);
    }

    // ---- blocks -------------------------------------------------------------------------------------------

    IBlockState getBlockState(BlockPos pos) {
        return getBlockState(pos.getX(), pos.getY(), pos.getZ());
    }

    IBlockState getBlockState(int x, int y, int z) {
        if (world.isOutsideBuildHeight(readPos.setPos(x, y, z))) {
            return Blocks.AIR.getDefaultState();
        }
        reads++;
        IBlockState written = journal.overlay(FeatureJournal.pack(x, y, z));
        if (written != null) {
            return written;
        }
        return chunk(x >> 4, z >> 4).getBlockState(x, y, z);
    }

    /** {@code World.setBlockState} for the job: returns whether the block changed. */
    boolean setBlockState(BlockPos pos, IBlockState state, int flags) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        if (world.isOutsideBuildHeight(pos)) {
            return false;
        }
        if (!FeatureWindow.inWrite(cx, cz, x >> 4, z >> 4)) {
            throw abort("write outside the window at " + x + "," + y + "," + z);
        }
        IBlockState old = getBlockState(x, y, z);
        if (old == state) {
            return false;
        }
        if ((hasTileEntity(state) && !placeableWithoutTileEntity(state)) || hasTileEntity(old)) {
            Block block = hasTileEntity(state) ? state.getBlock() : old.getBlock();
            throw abort("tile entity block " + block.getRegistryName() + " at " + x + "," + y + "," + z);
        }
        journal.write(FeatureJournal.pack(x, y, z), state, flags);
        long column = FeatureJournal.pack(x, 0, z);
        Integer top = writtenTop.get(column);
        if (top == null || y > top) {
            writtenTop.put(column, y);
        }
        int opacity = state.getLightOpacity(world, pos);
        int next = ColumnHeights.afterWrite(heightOf(x, z), x, y, z, opacity, this::opacityAt);
        heights.put(column, next);
        return true;
    }

    /** A tile entity block the config lists: the journal replay on the server creates its tile entity. */
    private static boolean placeableWithoutTileEntity(IBlockState state) {
        net.minecraft.util.ResourceLocation name = state.getBlock().getRegistryName();
        if (name == null) {
            return false;
        }
        String id = name.toString();
        for (String allowed : ChunkStewardConfig.features.tileEntityBlocks) {
            if (allowed != null && allowed.trim().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTileEntity(IBlockState state) {
        return state.getBlock().hasTileEntity(state);
    }

    private int opacityAt(int x, int y, int z) {
        IBlockState state = getBlockState(x, y, z);
        return state.getLightOpacity(world, scratch.setPos(x, y, z));
    }

    /** The final height of every column the job wrote to: {@code consumer(x, z, height)}. */
    void forEachHeight(HeightConsumer consumer) {
        heights.forEach((key, height) -> consumer.accept(FeatureJournal.unpackX(key), FeatureJournal.unpackZ(key), height));
    }

    interface HeightConsumer {
        void accept(int x, int z, int height);
    }

    // ---- heights ------------------------------------------------------------------------------------------

    /** {@code World.getHeight(x, z)}: the height map value, with the job's writes applied. */
    int heightOf(int x, int z) {
        Integer written = heights.get(FeatureJournal.pack(x, 0, z));
        if (written != null) {
            return written;
        }
        // The raw array, not getHeightValue: another mod hooks the getter to run a pending light replay, which a
        // worker must never do. The server thread settled those when the job was accepted (FeatureLane.submit).
        return chunk(x >> 4, z >> 4).getHeightMap()[(z & 15) << 4 | (x & 15)];
    }

    boolean canSeeSky(BlockPos pos) {
        return pos.getY() >= heightOf(pos.getX(), pos.getZ());
    }

    private int scanTop(int x, int z, int above) {
        int top = chunk(x >> 4, z >> 4).getTopFilledSegment() + above;
        Integer written = writtenTop.get(FeatureJournal.pack(x, 0, z));
        if (written != null) {
            top = Math.max(top, (written >> 4 << 4) + above);
        }
        return top;
    }

    /** {@code World.getPrecipitationHeight}, scanning the job's view instead of the cached height map. */
    BlockPos getPrecipitationHeight(BlockPos pos) {
        int x = pos.getX();
        int z = pos.getZ();
        int y = scanTop(x, z, 15);
        int found = -1;
        while (y > 0 && found == -1) {
            Material material = getBlockState(x, y, z).getMaterial();
            if (!material.blocksMovement() && !material.isLiquid()) {
                y--;
            } else {
                found = y + 1;
            }
        }
        return new BlockPos(x, found, z);
    }

    /** {@code World.getTopSolidOrLiquidBlock}, over the job's view. */
    BlockPos getTopSolidOrLiquidBlock(BlockPos pos) {
        int x = pos.getX();
        int z = pos.getZ();
        int y = scanTop(x, z, 16);
        while (y >= 0) {
            int below = y - 1;
            IBlockState state = getBlockState(x, below, z);
            BlockPos at = new BlockPos(x, below, z);
            Block block = state.getBlock();
            if (state.getMaterial().blocksMovement() && !block.isLeaves(state, world, at)
                    && !block.isFoliage(world, at)) {
                break;
            }
            y = below;
        }
        return new BlockPos(x, y, z);
    }
}
