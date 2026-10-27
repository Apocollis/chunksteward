package com.apocollis.chunksteward.server;

import net.minecraft.block.state.IBlockState;

/**
 * The top and filler block Twilight Forest's highlands biome picks for one column, held on the calling thread. The
 * biome writes them into its own shared {@code topBlock} / {@code fillerBlock} fields and reads them back in the same
 * call, so two threads generating highlands columns repaint each other. Holds no mod types.
 */
public final class ThreadSurfaceBlocks {
    private static final ThreadLocal<IBlockState[]> HELD = new ThreadLocal<IBlockState[]>();

    private ThreadSurfaceBlocks() {
    }

    /** Starts holding blocks for one column on this thread. Pair with {@link #exit}. */
    public static void enter() {
        HELD.set(new IBlockState[2]);
    }

    public static void exit() {
        HELD.remove();
    }

    /** Sets the held top block, or does nothing when this thread holds no column. */
    public static void setTop(IBlockState state) {
        IBlockState[] held = HELD.get();
        if (held != null) {
            held[0] = state;
        }
    }

    public static void setFiller(IBlockState state) {
        IBlockState[] held = HELD.get();
        if (held != null) {
            held[1] = state;
        }
    }

    /** The held top block, or {@code fallback} when this thread holds none. */
    public static IBlockState top(IBlockState fallback) {
        IBlockState[] held = HELD.get();
        return held != null && held[0] != null ? held[0] : fallback;
    }

    public static IBlockState filler(IBlockState fallback) {
        IBlockState[] held = HELD.get();
        return held != null && held[1] != null ? held[1] : fallback;
    }
}
