# Pacing API

`com.apocollis.chunksteward.api.ChunkStewardAPI`: read-only pacing information for mods that spread their own work across ticks (dungeon builders, structure pasters, anything that can run in slices). Chunk Steward already measures the tick for its own throttle; the API lets other mods use the same numbers instead of each computing idle deltas.

| Method | Returns |
| --- | --- |
| `API_VERSION` | `2`; incremented when methods are added, never changes the meaning of an existing method |
| `getAverageTickMs()` | average ms of whole-tick work over about the last 20 ticks (0 before the first measurement) |
| `getNonChunkTickMs()` | the same, excluding chunk provision (what entities, mods and everything else cost) |
| `getHeadroomMs()` | spare ms per tick on average: what is left of a 50 ms tick after the average, times `getLoadFactor()`, never below 0 |
| `getMeasuredTps()` (v2) | measured TPS over about 5 s: `min(20, 1000 / mean tick work)` |
| `getLoadFactor()` (v2) | 1.0 when measured TPS is at `throttle.tpsFull` (20: the server has room), 0.0 at `throttle.tpsLoaded` (15: under load), linear in between. Scale optional work by it |
| `getRemainingTickBudgetMs(double targetMs)` | `targetMs` minus the time the current tick has used so far, never below 0; returns `targetMs` off the server thread or before the first measurement. For in-tick decisions |
| `isServerBehind()` | measured TPS is below `throttle.tpsFull`: the server is not holding a full 20 TPS |
| `isGeneratingChunks()` | chunks are being generated for players at the rate where preload holds off (`preload.busyChunks` in `preload.busyWindowTicks`); a good moment to pause optional work. Chunks a mod in `preload.busyIgnoreMods` (default `roguelike`) generates on its own Forge ticket are not counted, so a dungeon build does not see its own footprint loading as player demand |
| `getPopulateQueueSize()` | chunks whose populate is queued behind the tick budget |

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

## Related, no API needed

Mods that load chunks across ticks, force them with a Forge chunk ticket and then call `chunk.populate` themselves (Roguelike Dungeons) are covered by `populate.ticketExemptMods` in the config (see [populate.md](populate.md)).
