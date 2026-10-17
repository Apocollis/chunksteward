package com.apocollis.chunksteward.config;

import com.apocollis.chunksteward.ChunkSteward;
import net.minecraftforge.common.config.Config;

@Config(modid = ChunkSteward.MODID, name = "chunksteward", category = "")
public class ChunkStewardConfig {

    public static Throttle throttle = new Throttle();
    public static Ordering ordering = new Ordering();
    public static Populate populate = new Populate();
    public static RandomTicks randomTicks = new RandomTicks();
    public static Preload preload = new Preload();
    public static Stage2 stage2 = new Stage2();
    public static Light light = new Light();
    public static Dimensions dimensions = new Dimensions();
    public static Logging logging = new Logging();

    public static class Throttle {
        @Config.Comment("Master switch for the adaptive chunk throttle.")
        public boolean enabled = true;

        @Config.Comment({"While the average tick work (about the last 20 ticks) is under 50 ms the server runs at 20 TPS: admit",
                "chunks whenever the current tick has not used 50 ms yet. Above that average, targetTickMs applies.",
                "false = only the targetTickMs headroom rule (the behaviour before 0.3's pacing API)."})
        public boolean admitAtFullTps = true;

        @Config.Comment("Target server tick work in ms. Chunk generation only gets the headroom left under this.")
        @Config.RangeDouble(min = 5.0, max = 50.0)
        public double targetTickMs = 40.0;

        @Config.Comment({"Measured TPS (over about the last 5 seconds) at or above which the server counts as having room.",
                "Reported only (status log, /chunksteward status, ChunkStewardAPI.getLoadFactor and isServerBehind); it",
                "does not gate chunks. Measured TPS is min(20, 1000 / average tick work), so any average under 50 ms reads 20."})
        @Config.RangeDouble(min = 5.0, max = 20.0)
        public double tpsFull = 20.0;

        @Config.Comment("Measured TPS at or below which the server counts as under load (load factor 0). Reported only, see tpsFull.")
        @Config.RangeDouble(min = 1.0, max = 19.9)
        public double tpsLoaded = 15.0;

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

    public static class Populate {
        @Config.Comment({"Spread chunk populate (trees, ores, structures, every mod's world generators) across ticks.",
                "A populate that would push the current tick past tickBudgetMs is queued and run later; chunks are not",
                "sent to players until populated, so queued chunks only arrive later. Nothing is cancelled."})
        public boolean enabled = true;

        @Config.Comment("A populate may start only while the current tick has used less than this many ms.")
        @Config.RangeDouble(min = 5.0, max = 50.0)
        public double tickBudgetMs = 30.0;

        @Config.Comment("Guarantee at least one populate every this many ticks, even on a busy server.")
        @Config.RangeInt(min = 1, max = 200)
        public int maxStarveTicks = 4;

        @Config.Comment("Chunks within this many chunks of a player (or the speed-scaled emergency radius) populate at once.")
        @Config.RangeInt(min = 0, max = 8)
        public int protectRadius = 2;

        @Config.Comment("At most this many queued populates are run per tick (within the tick budget).")
        @Config.RangeInt(min = 1, max = 64)
        public int drainPerTick = 3;

        @Config.Comment({"A chunk generated while another chunk is populating (a cascade: a generator read or wrote past",
                "the loaded area) is generated terrain only and populated later through the queue, instead of inside the",
                "running populate. Ore and structure placement still sees real terrain."})
        public boolean cascadeDeferral = true;

        @Config.Comment({"Mod ids whose Forge chunk tickets exempt a chunk from populate deferral: a chunk held by a ticket",
                "owned by one of these mods populates immediately (and is taken out of the populate queue), never later.",
                "For mods that load chunks across ticks and then call populate themselves, such as Roguelike Dungeons",
                "(roguelike): a deferred populate would run after the mod built on the chunk and could overwrite it.",
                "Cascades are never exempt. Empty list = off."})
        public String[] ticketExemptMods = {"roguelike"};

        @Config.Comment("Ticks a cascade-generated chunk stays loaded before it is released if no player owns it.")
        @Config.RangeInt(min = 20, max = 6000)
        public int cascadeHoldTicks = 200;

        @Config.Comment("Cap on queued populates; past it chunks populate at once.")
        @Config.RangeInt(min = 16, max = 65536)
        public int maxQueued = 4096;
    }

    public static class RandomTicks {
        @Config.Comment({"While the world runs its random block ticks (lava and fire spread, grass spread), a read or write",
                "in a chunk that is not loaded sees an empty chunk instead of generating it. Nothing is lost: the spread",
                "simply does not cross into unloaded terrain, as in newer Minecraft versions."})
        public boolean guardEnabled = true;
    }

    public static class Preload {
        @Config.Comment("Generate chunks beyond the view distance around players while the server has spare time.")
        public boolean enabled = true;

        @Config.Comment({"Players standing still for this many ticks (20 = 1 s) get the idle tier: full generation out to",
                "view distance + idleExtraFull, terrain only out to view distance + idleExtraTerrain."})
        @Config.RangeInt(min = 1, max = 1200)
        public int idleStillTicks = 20;

        @Config.Comment("Idle tier: full generation (terrain and populate) this many chunks past the view distance.")
        @Config.RangeInt(min = 0, max = 32)
        public int idleExtraFull = 2;

        @Config.Comment("Idle tier: terrain-only generation (no populate) this many chunks past the view distance. 0 = off.")
        @Config.RangeInt(min = 0, max = 48)
        public int idleExtraTerrain = 12;

        @Config.Comment("Idle tier runs only when the average tick and the current tick are both under this many ms.")
        @Config.RangeDouble(min = 1.0, max = 50.0)
        public double maxTickMs = 20.0;

        @Config.Comment({"Moving tier: slow players (walking, sprinting) get a terrain-only cone ahead of them. False keeps",
                "preload to standing-still players only."})
        public boolean movingEnabled = true;

        @Config.Comment("Moving tier: terrain-only generation this many chunks past the view distance, ahead of the player.")
        @Config.RangeInt(min = 1, max = 16)
        public int movingExtraTerrain = 3;

        @Config.Comment("Moving tier: half-angle of the forward cone, in degrees.")
        @Config.RangeDouble(min = 10.0, max = 180.0)
        public double movingForwardAngleDegrees = 60.0;

        @Config.Comment("Moving tier is off above this speed (blocks per tick). Sprinting is about 0.28, elytra well above 0.5.")
        @Config.RangeDouble(min = 0.0, max = 10.0)
        public double movingMaxSpeed = 0.30;

        @Config.Comment("Moving tier runs only when the average tick and the current tick are both under this many ms.")
        @Config.RangeDouble(min = 1.0, max = 50.0)
        public double movingMaxTickMs = 15.0;

        @Config.Comment({"Preload holds off while this many chunks (not counting preload's own) were generated in the last",
                "busyWindowTicks ticks: the server is busy generating chunks players need."})
        @Config.RangeInt(min = 1, max = 1000)
        public int busyChunks = 6;

        @Config.Comment({"Mod ids whose Forge chunk tickets mark generation as the mod's own work, not player demand: a chunk",
                "generated while held by a ticket owned by one of these mods does not count toward busyChunks, so preload,",
                "stage 2 and ChunkStewardAPI.isGeneratingChunks() do not report it. For mods that force their footprint",
                "chunks on a ticket right before loading them and pace themselves on isGeneratingChunks(), such as",
                "Roguelike Dungeons (roguelike), which otherwise paused its own build. Empty list = count everything."})
        public String[] busyIgnoreMods = {"roguelike"};

        @Config.Comment("Window for busyChunks, in ticks (100 = 5 s, at most 250).")
        @Config.RangeInt(min = 1, max = 250)
        public int busyWindowTicks = 100;

        @Config.Comment("Preload holds off while this many populates are queued.")
        @Config.RangeInt(min = 0, max = 4096)
        public int maxPopulateBacklog = 4;

        @Config.Comment("At most one chunk is generated every this many ticks.")
        @Config.RangeInt(min = 1, max = 200)
        public int intervalTicks = 2;

        @Config.Comment("At most this many chunks are preloaded per player per minute.")
        @Config.RangeInt(min = 1, max = 6000)
        public int maxPerMinute = 90;

        @Config.Comment("Ticks a fully preloaded chunk stays loaded so its neighbours can populate it, then it is released.")
        @Config.RangeInt(min = 20, max = 6000)
        public int holdTicks = 200;

        @Config.Comment("Safety cap on chunks held loaded by preload and cascades together.")
        @Config.RangeInt(min = 16, max = 4096)
        public int maxHeldChunks = 600;
    }

    public static class Stage2 {
        @Config.Comment({"Home-area pregen. Areas where players spend time (a bed or respawn point, or a place where they",
                "stay about dwellMinutes in total) become hotspots. While a player is near one, and the server has spare",
                "time, the area is generated terrain-only out to radiusTerrain, then populated out to radiusFull.",
                "NOTE: a pregenerated area keeps the worldgen settings of the moment; later worldgen config changes only",
                "affect chunks generated after them. Turn this off on worlds where you test worldgen."})
        public boolean enabled = true;

        @Config.Comment("Chunks within this square radius of a hotspot are fully generated and populated.")
        @Config.RangeInt(min = 1, max = 64)
        public int radiusFull = 20;

        @Config.Comment("Past radiusFull, terrain only out to this square radius. Must be larger than radiusFull.")
        @Config.RangeInt(min = 2, max = 96)
        public int radiusTerrain = 30;

        @Config.Comment("Accumulated time (in a 3x3 block of cells) that turns a place into a hotspot, in minutes.")
        @Config.RangeInt(min = 1, max = 1440)
        public int dwellMinutes = 10;

        @Config.Comment({"Dwell is sampled every this many seconds. A player within dwellAnchorRadiusBlocks of their anchor",
                "is idle and credits the sample to their cell; farther away the anchor moves to where they are."})
        @Config.RangeInt(min = 1, max = 300)
        public int dwellSampleSeconds = 10;

        @Config.Comment("Horizontal distance from the anchor, in blocks, within which a player counts as staying in place.")
        @Config.RangeInt(min = 4, max = 256)
        public int dwellAnchorRadiusBlocks = 32;

        @Config.Comment("Dwell cells are this many chunks wide.")
        @Config.RangeInt(min = 1, max = 64)
        public int cellSizeChunks = 8;

        @Config.Comment("A new hotspot within this many chunks of an existing one is merged into it.")
        @Config.RangeInt(min = 1, max = 64)
        public int mergeRadiusChunks = 8;

        @Config.Comment("A player's bed or respawn point becomes a hotspot at once.")
        public boolean homeFromBed = true;

        @Config.Comment("A hotspot is worked on only while a player is within this many chunks of it.")
        @Config.RangeInt(min = 1, max = 64)
        public int activeRadiusChunks = 8;

        @Config.Comment("At most one operation (a generation or a populate) every this many ticks.")
        @Config.RangeInt(min = 1, max = 200)
        public int intervalTicks = 4;

        @Config.Comment("At most this many operations per hotspot per minute.")
        @Config.RangeInt(min = 1, max = 6000)
        public int maxPerMinute = 120;

        @Config.Comment("Runs only when the average tick and the current tick are both under this many ms.")
        @Config.RangeDouble(min = 1.0, max = 50.0)
        public double maxTickMs = 20.0;

        @Config.Comment("Log a warning (and tell operators) once this many chunks have been pregenerated by stage 2. 0 = off.")
        @Config.RangeInt(min = 0, max = 100000000)
        public int warnTotalChunks = 100000;

        @Config.Comment("Hard limit on chunks pregenerated by stage 2 (all dimensions). 0 = no limit.")
        @Config.RangeInt(min = 0, max = 100000000)
        public int maxTotalChunks = 0;
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

        @Config.Comment({"TEMPORARY diagnostics, verbose: every second, why chunk provision calls were passed, bypassed,",
                "admitted or deferred and what the populate gate did, plus a scan of the chunks around each player",
                "(missing, loaded but unpopulated, and why). Every line starts with 'dbg': grep dbg latest.log."})
        public boolean debug = false;

        @Config.Comment("Number of slowest world generators (IWorldGenerator classes) listed in the status log.")
        @Config.RangeInt(min = 0, max = 50)
        public int topGenerators = 8;
    }
}
