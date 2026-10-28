# Pacing API

`com.apocollis.chunksteward.api.ChunkStewardAPI`: read-only pacing information for mods that spread their own work across ticks (dungeon builders, structure pasters, anything that can run in slices). Chunk Steward already measures the tick for its own throttle; the API lets other mods use the same numbers instead of each computing idle deltas.

| Method | Returns |
| --- | --- |
| `API_VERSION` | `3`; incremented when methods are added, never changes the meaning of an existing method |
| `getAverageTickMs()` | average ms of whole-tick work over about the last 20 ticks (0 before the first measurement) |
| `getNonChunkTickMs()` | the same, excluding chunk provision (what entities, mods and everything else cost) |
| `getHeadroomMs()` | spare ms per tick on average: what is left of a 50 ms tick after the average, times `getLoadFactor()`, never below 0 |
| `getMeasuredTps()` (v2) | measured TPS over about 5 s: `min(20, 1000 / mean tick work)` |
| `getLoadFactor()` (v2) | 1.0 when measured TPS is at or above `throttle.tpsFull` (19.0: the server has room), 0.0 at `throttle.tpsLoaded` (15: under load), linear in between. Scale optional work by it |
| `getRemainingTickBudgetMs(double targetMs)` | `targetMs` minus the time the current tick has used so far, never below 0; returns `targetMs` off the server thread or before the first measurement. For in-tick decisions |
| `isServerBehind()` | measured TPS is below `throttle.tpsFull` (19.0): the server is not holding a full 20 TPS |
| `isGeneratingChunks()` | chunks are being generated for players at the rate where preload holds off (`preload.busyChunks` in `preload.busyWindowTicks`); a good moment to pause optional work. Chunks a mod in `preload.busyIgnoreMods` (default `roguelike`) generates on its own Forge ticket are not counted, so a dungeon build does not see its own footprint loading as player demand |
| `getPopulateQueueSize()` | chunks whose populate is queued behind the tick budget |

`getHeadroomMs()` is the 20-tick average headroom scaled by the load factor: spare time per tick on average, not a per-tick guarantee.

**Treat a load factor of 0.9 or more as full.** `throttle.tpsFull` defaults to 19.0, so any measured TPS of 19.0 or higher reads 1.0, but a test of exactly `== 1.0` still flips on a brief overrun (a 100-tick mean of 50.3 ms is 19.88 TPS). At load factor 1 the server has room: spend up to `getHeadroomMs()` each tick and do not pause for chunk generation alone. Scale back, or pause, only when the load factor stays low.

Call it on the server thread. The averages lag by about 20 ticks: use them for pacing (how much to attempt per tick), not for exact decisions about the current tick.

## Using it from another mod

Compile against the Chunk Steward jar (add `ChunkSteward-<version>.jar` to the compile classpath, for example `modCompileOnly` from `libs/`). Never call the API unguarded: keep the calls in a small holder class that is only loaded when Chunk Steward is present, so a missing mod costs nothing and cannot throw `NoClassDefFoundError`.

```java
public final class StewardPacing {
    private static final boolean PRESENT = Loader.isModLoaded("chunksteward");

    private StewardPacing() {}

    /** ms this tick can still spend, within {@code targetMs}; {@code targetMs} when Chunk Steward is absent. */
    public static double remainingMs(double targetMs) {
        return PRESENT ? Api.remaining(targetMs) : targetMs;
    }

    public static boolean shouldPauseOptionalWork() {
        return PRESENT && Api.busy();
    }

    // Only loaded (and so ChunkStewardAPI only resolved) when PRESENT is true.
    private static final class Api {
        static double remaining(double targetMs) {
            return ChunkStewardAPI.getRemainingTickBudgetMs(targetMs);
        }

        static boolean busy() {
            return ChunkStewardAPI.isServerBehind() || ChunkStewardAPI.isGeneratingChunks();
        }
    }
}
```

Add `after:chunksteward` to the consumer's `@Mod` dependencies for load order (optional, not required-after). Do not let correctness depend on the API: if it is absent the mod must behave exactly as it does today.

Version 2 methods above are unchanged. A class that calls a version 3 method must be loaded only after `API_VERSION >= 3`. Linking that method against a jar that is still version 2 throws `NoSuchMethodError` when the class loads, even if the call itself is inside an `if`.

## Version 3 (terrain pipeline)

These read the worker pipeline. `isPipelineActive` is whether workers may take terrain. `isGeneratingChunks` is still the player-demand rate: Roguelike should keep using that, with `isServerBehind`, to decide when to pause a dungeon. `getPipelineQueueSize` counts queued, in-flight, and finished terrain, including preload and stage 2, so a large number means chunks are held ahead of the server thread.

| Method | Who calls it | What it means |
| --- | --- | --- |
| `isPipelineActive()` | server thread | Workers are running, not killed, not blocked by LoliASM, and the CPU-guard cap is above 0. |
| `getPipelineQueueSize()` | server thread | Queued + claimed by a worker + claimed by the server thread + finished and waiting to install. |
| `getAverageTerrainInstallMs()` | server thread | Smoothed milliseconds to install a finished chunk (`recreateStructures`). 0 until the first install. |
| `isChunkReady(dimension, x, z)` | server thread | True when a worker chunk is waiting. The next `provideChunk` for that position installs it. False when the world is not loaded, the chunk was never submitted, or it is still generating. False does not mean it will never be ready. |
| `isPipelineContext()` | worker thread, inside `generateChunk`; also the server thread while it copies a worker generator | True there. Tweaks must not write the shared village stash when this is true. Verify is not a pipeline context. |
| `currentJobContext()` | worker thread, inside `generateChunk` | The array `prepare` returned, in registration order, or null when nobody registered and when called off a job. One preparer is a one-element array. |
| `registerJobPreparer(JobPreparer)` | server thread, once, at mod init | `prepare(world, x, z)` runs on the server thread when a position is submitted and returns an immutable snapshot. `isStillValid` runs on the server thread at install. False discards the worker chunk and the server generates it. |

Roguelike can use `isChunkReady` before it forces a footprint chunk: true means loading that chunk installs the worker result. It should still pace the build with `isGeneratingChunks` and `getRemainingTickBudgetMs`, not with `getPipelineQueueSize`.

Tweaks uses `isPipelineContext` around any stash of the live generator, and `registerJobPreparer` when a worker job needs a snapshot that must not be read from shared maps. The object from `prepare` is what `currentJobContext()` returns on the worker. It must not hold the live world.

## Related, no API needed

Mods that load chunks across ticks, force them with a Forge chunk ticket and then call `chunk.populate` themselves (Roguelike Dungeons) are covered by `populate.ticketExemptMods` in the config (see [populate.md](populate.md)).
