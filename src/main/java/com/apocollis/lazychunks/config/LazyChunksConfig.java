package com.apocollis.lazychunks.config;

import com.apocollis.lazychunks.LazyChunks;
import net.minecraftforge.common.config.Config;

@Config(modid = LazyChunks.MODID, name = "lazychunks", category = "")
public class LazyChunksConfig {

    public static Throttle throttle = new Throttle();
    public static Ordering ordering = new Ordering();
    public static Preload preload = new Preload();
    public static Light light = new Light();
    public static Dimensions dimensions = new Dimensions();
    public static Logging logging = new Logging();

    public static class Throttle {
        @Config.Comment("Master switch for the adaptive chunk throttle.")
        public boolean enabled = true;

        @Config.Comment("Target server tick work in ms. Chunk generation only gets the headroom left under this.")
        @Config.RangeDouble(min = 5.0, max = 50.0)
        public double targetTickMs = 40.0;

        @Config.Comment("When the average non-chunk tick work is below this many ms the server is idle and chunks load at full vanilla speed.")
        @Config.RangeDouble(min = 0.0, max = 50.0)
        public double idleTickMs = 15.0;

        @Config.Comment("Guarantee at least one generated chunk every this many ticks (4 = 0.2 s), so loading never starves.")
        @Config.RangeInt(min = 1, max = 200)
        public int maxStarveTicks = 4;

        @Config.Comment("Ticks after a player joins during which their chunks load without limits (200 = 10 s).")
        @Config.RangeInt(min = 0, max = 6000)
        public int warmupTicks = 200;

        @Config.Comment("Chunks within this many chunks of a player (0 = only the chunk they stand in) always load immediately.")
        @Config.RangeInt(min = 0, max = 4)
        public int emergencyRadius = 1;

        @Config.Comment({"Fast players get a larger emergency radius: emergencyRadius + ceil(speed * lookaheadTicks / 16),",
                "speed in blocks per tick. 0 turns the speed scaling off."})
        @Config.RangeInt(min = 0, max = 200)
        public int emergencyLookaheadTicks = 20;

        @Config.Comment("Below this speed (blocks per tick) the emergency radius is not scaled. Sprinting is about 0.28.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public double emergencyMinSpeed = 0.3;

        @Config.Comment("Cap on the speed-scaled emergency radius, in chunks.")
        @Config.RangeInt(min = 0, max = 8)
        public int emergencyMaxRadius = 5;

        @Config.Comment("Pause throttled loading on the tick of a world save (autosave every 900 ticks, /save-all) and this many ticks after.")
        @Config.RangeInt(min = 0, max = 20)
        public int savePauseTicks = 1;
    }

    public static class Ordering {
        @Config.Comment("Order missing chunks by distance and by angle from the player's direction instead of distance only.")
        public boolean enabled = true;

        @Config.Comment({"How much more a chunk directly behind the player costs than one straight ahead at the same distance.",
                "1.0 = behind counts as twice as far. 0 = vanilla order exactly."})
        @Config.RangeDouble(min = 0.0, max = 8.0)
        public double directionPenalty = 1.0;

        @Config.Comment("Re-sort the missing chunks when a player's direction turns by more than this many degrees. 0 = never.")
        @Config.RangeDouble(min = 0.0, max = 180.0)
        public double resortAngleDegrees = 30.0;

        @Config.Comment("Below this speed (blocks per tick) the view direction is used.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public double velocityMinSpeed = 0.35;

        @Config.Comment("At or above this speed (blocks per tick) the travel direction is used; in between the two are blended.")
        @Config.RangeDouble(min = 0.01, max = 10.0)
        public double velocityFullSpeed = 0.8;
    }

    public static class Preload {
        @Config.Comment("Generate ungenerated chunks around players who stand still while the server is not busy.")
        public boolean enabled = true;

        @Config.Comment("Chunk radius around the player to preload fully (terrain and populate).")
        @Config.RangeInt(min = 1, max = 32)
        public int radius = 10;

        @Config.Comment({"Past radius, generate terrain only (no populate) out to this total radius. 0 = off.",
                "Those chunks are saved unpopulated; vanilla populates them when the player comes near."})
        @Config.RangeInt(min = 0, max = 32)
        public int terrainOnlyRadius = 20;

        @Config.Comment("At most this many terrain-only chunks per stationary spell.")
        @Config.RangeInt(min = 0, max = 4096)
        public int terrainOnlyMaxChunks = 600;

        @Config.Comment("Ticks a player must stand still before preloading starts.")
        @Config.RangeInt(min = 1, max = 1200)
        public int stationaryTicks = 40;

        @Config.Comment("At most one chunk is generated every this many ticks.")
        @Config.RangeInt(min = 1, max = 200)
        public int intervalTicks = 2;

        @Config.Comment("Preload only runs when the average tick and the current tick are both under this many ms.")
        @Config.RangeDouble(min = 1.0, max = 50.0)
        public double maxTickMs = 20.0;

        @Config.Comment("Ticks a preloaded chunk stays loaded so its neighbours can populate it, then it is released.")
        @Config.RangeInt(min = 20, max = 6000)
        public int holdTicks = 200;

        @Config.Comment("Safety cap on chunks held loaded by preload.")
        @Config.RangeInt(min = 16, max = 4096)
        public int maxHeldChunks = 600;
    }

    public static class Light {
        @Config.Comment({"Defer the per-tick sky light retry (Chunk.onTick -> checkLight) for chunks away from players and",
                "outside their travel direction while chunk generation is heavy. A deferred chunk is not sent to clients",
                "until its light pass runs."})
        public boolean deferEnabled = false;

        @Config.Comment("Chunks within this many chunks of a player are never deferred.")
        @Config.RangeInt(min = 0, max = 8)
        public int protectRadius = 2;

        @Config.Comment("Generation counts as heavy when chunk provision averages at least this many ms per tick.")
        @Config.RangeDouble(min = 0.0, max = 50.0)
        public double heavyGenMs = 5.0;

        @Config.Comment("At most this many deferred light passes per tick.")
        @Config.RangeInt(min = 1, max = 256)
        public int drainPerTick = 8;

        @Config.Comment("Time budget for deferred light passes per tick, in ms.")
        @Config.RangeDouble(min = 0.1, max = 20.0)
        public double drainBudgetMs = 2.0;

        @Config.Comment("Cap on queued light passes; past it chunks are lit at once.")
        @Config.RangeInt(min = 16, max = 65536)
        public int maxQueued = 4096;
    }

    public static class Dimensions {
        @Config.Comment("Dimension ids the mod applies to. Empty list with blacklist mode = all dimensions.")
        public int[] list = new int[0];

        @Config.Comment("true: the list is a whitelist. false: the list is a blacklist.")
        public boolean whitelist = false;
    }

    public static class Logging {
        @Config.Comment({"Log a status summary every this many seconds. 0 disables.",
                "Stage and world generator timing is only collected while this is above 0."})
        @Config.RangeInt(min = 0, max = 3600)
        public int statusIntervalSeconds = 0;

        @Config.Comment("Number of slowest world generators (IWorldGenerator classes) listed in the status log.")
        @Config.RangeInt(min = 0, max = 50)
        public int topGenerators = 8;
    }
}
