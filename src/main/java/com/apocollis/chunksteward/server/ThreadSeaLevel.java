package com.apocollis.chunksteward.server;

import net.minecraft.world.World;

/**
 * Sea level set by NetherEx biomes during {@code buildSurface}, held on the calling thread for that world instead of
 * the shared world field. Other threads and other worlds keep reading the field.
 */
public final class ThreadSeaLevel {
    private static final ThreadLocal<Entry> CURRENT = new ThreadLocal<Entry>();

    private ThreadSeaLevel() {
    }

    private static final class Entry {
        final World world;
        int level;
        final Entry previous;

        Entry(World world, int level, Entry previous) {
            this.world = world;
            this.level = level;
            this.previous = previous;
        }
    }

    /** Starts holding {@code world}'s level on this thread, from its current value. Pair with {@link #exit}. */
    public static void enter(World world) {
        CURRENT.set(new Entry(world, world.getSeaLevel(), CURRENT.get()));
    }

    /** Restores the entry that was current before the matching {@link #enter}. */
    public static void exit() {
        Entry entry = CURRENT.get();
        if (entry == null) {
            return;
        }
        if (entry.previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(entry.previous);
        }
    }

    /** Sets the held level, or the world field when this thread holds none for {@code world}. */
    public static void set(World world, int level) {
        Entry entry = find(world);
        if (entry == null) {
            world.setSeaLevel(level);
        } else {
            entry.level = level;
        }
    }

    /** True when this thread holds a level for {@code world}; read it with {@link #level}. */
    public static boolean holds(World world) {
        return find(world) != null;
    }

    public static int level(World world) {
        Entry entry = find(world);
        return entry == null ? world.getSeaLevel() : entry.level;
    }

    public static void clear() {
        CURRENT.remove();
    }

    private static Entry find(World world) {
        for (Entry entry = CURRENT.get(); entry != null; entry = entry.previous) {
            if (entry.world == world) {
                return entry;
            }
        }
        return null;
    }
}
