package com.apocollis.chunksteward.data;

import com.apocollis.chunksteward.ChunkSteward;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Per-dimension saved data: the hotspots stage 2 works on and the dwell counters that create them. Lives in the
 * dimension's own data folder ({@code World.getPerWorldStorage()}) and is saved with the world, so progress and
 * dwell time survive restarts. A missing or unreadable file only restarts the counters; the world is never touched.
 */
public class HotspotData extends WorldSavedData {
    public static final String NAME = ChunkSteward.MODID + "_hotspots";

    public static final int SOURCE_BED = 0;
    public static final int SOURCE_DWELL = 1;
    public static final int SOURCE_MANUAL = 2;

    private static final int MAX_CELLS = 4096;

    public static final class Hotspot {
        public int id;
        /** Center chunk. */
        public int x;
        public int z;
        public int source;
        /** Last completed square ring of the terrain phase / populate phase, or -1. */
        public int terrainRing = -1;
        public int popRing = -1;
        /** Chunks generated (terrain) and populated by stage 2 for this hotspot. */
        public int generated;
        public int populated;
        /** World time of the last operation. */
        public long lastWorked;
    }

    public final List<Hotspot> hotspots = new ArrayList<>();
    /** Dwell seconds per cell, keyed by {@link ChunkPos#asLong} of the cell coordinates. */
    public final Long2IntOpenHashMap dwell = new Long2IntOpenHashMap();
    public int nextId = 1;
    /** Chunks stage 2 has generated in this dimension, for the disk warning. */
    public long pregenTotal;

    public HotspotData(String name) {
        super(name);
    }

    public static HotspotData get(WorldServer world) {
        MapStorage storage = world.getPerWorldStorage();
        HotspotData data = (HotspotData) storage.getOrLoadData(HotspotData.class, NAME);
        if (data == null) {
            data = new HotspotData(NAME);
            storage.setData(NAME, data);
        }
        return data;
    }

    // ---- hotspots -------------------------------------------------------------------------------------------------

    public Hotspot find(int id) {
        for (Hotspot h : hotspots) {
            if (h.id == id) {
                return h;
            }
        }
        return null;
    }

    /** The hotspot whose center is within {@code radius} chunks (square) of the chunk, or null. */
    public Hotspot near(int cx, int cz, int radius) {
        for (Hotspot h : hotspots) {
            if (Math.abs(h.x - cx) <= radius && Math.abs(h.z - cz) <= radius) {
                return h;
            }
        }
        return null;
    }

    public Hotspot add(int cx, int cz, int source) {
        Hotspot h = new Hotspot();
        h.id = nextId++;
        h.x = cx;
        h.z = cz;
        h.source = source;
        hotspots.add(h);
        markDirty();
        return h;
    }

    public boolean remove(int id) {
        Iterator<Hotspot> it = hotspots.iterator();
        while (it.hasNext()) {
            Hotspot h = it.next();
            if (h.id == id) {
                it.remove();
                markDirty();
                return true;
            }
        }
        return false;
    }

    // ---- dwell ----------------------------------------------------------------------------------------------------

    public int credit(int cellX, int cellZ, int seconds) {
        long key = ChunkPos.asLong(cellX, cellZ);
        int total = dwell.addTo(key, seconds) + seconds;
        if (dwell.size() > MAX_CELLS) {
            prune();
        }
        markDirty();
        return total;
    }

    public int cell(int cellX, int cellZ) {
        return dwell.get(ChunkPos.asLong(cellX, cellZ));
    }

    /** Seconds accumulated in the 3x3 block of cells around the cell. */
    public int sum3x3(int cellX, int cellZ) {
        int sum = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                sum += dwell.get(ChunkPos.asLong(cellX + dx, cellZ + dz));
            }
        }
        return sum;
    }

    public void clear3x3(int cellX, int cellZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                dwell.remove(ChunkPos.asLong(cellX + dx, cellZ + dz));
            }
        }
        markDirty();
    }

    /** Drops the lowest quarter of the counters. */
    private void prune() {
        int[] values = dwell.values().toIntArray();
        java.util.Arrays.sort(values);
        int cut = values[values.length / 4];
        Iterator<Long2IntMap.Entry> it = dwell.long2IntEntrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getIntValue() <= cut) {
                it.remove();
            }
        }
    }

    // ---- NBT ------------------------------------------------------------------------------------------------------

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        hotspots.clear();
        dwell.clear();
        nextId = Math.max(1, tag.getInteger("nextId"));
        pregenTotal = tag.getLong("pregenTotal");
        NBTTagList list = tag.getTagList("hotspots", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound t = list.getCompoundTagAt(i);
            Hotspot h = new Hotspot();
            h.id = t.getInteger("id");
            h.x = t.getInteger("x");
            h.z = t.getInteger("z");
            h.source = t.getInteger("source");
            h.terrainRing = t.hasKey("terrainRing") ? t.getInteger("terrainRing") : -1;
            h.popRing = t.hasKey("popRing") ? t.getInteger("popRing") : -1;
            h.generated = t.getInteger("generated");
            h.populated = t.getInteger("populated");
            h.lastWorked = t.getLong("lastWorked");
            hotspots.add(h);
            nextId = Math.max(nextId, h.id + 1);
        }
        NBTTagList cells = tag.getTagList("dwell", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound t = cells.getCompoundTagAt(i);
            dwell.put(t.getLong("k"), t.getInteger("s"));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        tag.setInteger("nextId", nextId);
        tag.setLong("pregenTotal", pregenTotal);
        NBTTagList list = new NBTTagList();
        for (Hotspot h : hotspots) {
            NBTTagCompound t = new NBTTagCompound();
            t.setInteger("id", h.id);
            t.setInteger("x", h.x);
            t.setInteger("z", h.z);
            t.setInteger("source", h.source);
            t.setInteger("terrainRing", h.terrainRing);
            t.setInteger("popRing", h.popRing);
            t.setInteger("generated", h.generated);
            t.setInteger("populated", h.populated);
            t.setLong("lastWorked", h.lastWorked);
            list.appendTag(t);
        }
        tag.setTag("hotspots", list);
        NBTTagList cells = new NBTTagList();
        for (Long2IntMap.Entry e : dwell.long2IntEntrySet()) {
            NBTTagCompound t = new NBTTagCompound();
            t.setLong("k", e.getLongKey());
            t.setInteger("s", e.getIntValue());
            cells.appendTag(t);
        }
        tag.setTag("dwell", cells);
        return tag;
    }
}
