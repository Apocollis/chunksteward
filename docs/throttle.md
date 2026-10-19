# Adaptive throttle

Hook: `PlayerChunkMap.tick` calls `providePlayerChunk(canGenerate)` for each entry whose chunk is missing. Vanilla stops at 50 ms or 49 chunks and always lets the first success through. `ChunkThrottle.provide` wraps that call. A `false` return keeps the entry queued for a later tick. The order in which entries are offered is set by [ordering.md](ordering.md).

- `canGenerate == false` (disk load only) and dimensions excluded by the dimension list pass straight through.
- **Busy signal:** `TickMonitor` keeps an EMA (alpha 0.1, about 20 ticks) of tick work *excluding* chunk provision, measured from `MinecraftServer.tick` HEAD to RETURN. A second EMA of chunk provision time (`chunkEmaMs`) is the "heavy generation" signal for light deferral.
- **Idle:** EMA below `idleTickMs` → full vanilla speed.
- **Idle:** EMA below `idleTickMs` (15) → full vanilla speed.
- **Full TPS** (`admitAtFullTps`, default on): while the server holds 20 TPS, measured TPS at `tpsFull` (100-tick mean at or under 50 ms) or the whole-tick EMA (about 20 ticks) under 50 ms, every chunk is admitted, however long the current tick already is. Vanilla's own loop still stops 50 ms after a success. If chunk work pushes the mean past 50 ms, TPS falls below 20 and the headroom rule below takes over. (The first version also required the current tick to be under 50 ms; dropped 2026-10-17 at the user's request: at 20 TPS there is no reason to hold a chunk.) In this pack one chunk costs 25 to 50 ms, more than the headroom below, so without this the headroom rule held chunks to the starvation rate (5 a second) at 20 TPS (DEVBOX log 2026-10-17 02:24: `admitted=5, deferred: headroom=1000-3600` per second with non-chunk work about 20 ms).
- **Busy:** above that average (or with `admitAtFullTps = false`), a chunk may generate only if `chunk time this tick + EMA cost of one chunk <= targetTickMs - EMA` (`targetTickMs` 40).
- **Measured TPS and load factor (reported only):** `min(20, 1000 / mean tick work over the last 100 ticks)`, and `clamp((TPS - tpsLoaded) / (tpsFull - tpsLoaded), 0, 1)` (19.5 / 15). Shown in the status log and `/chunksteward status` and returned by `ChunkStewardAPI` (`getMeasuredTps`, `getLoadFactor`, `isServerBehind`, `getHeadroomMs`); only `tpsFull` also feeds the full-TPS admission above; the rest does not gate chunks. In 0.3 development the throttle briefly admitted chunks by TPS-driven credit instead (and later by a feedback budget); test runs on DEVBOX (2026-10-17) showed chunks held and arriving in bursts at 20 TPS, so admission went back to the per-tick headroom above, which had worked, while the API kept its methods.
- **Starvation guard:** at least one generated chunk every `maxStarveTicks` ticks (default 4 = 0.2 s).
- **Emergency bypass:** an entry within the emergency radius of a watching player always loads. Radius per player:
  `emergencyRadius`, and once the player's horizontal speed (from `PlayerMotion`, blocks per tick, sampled every `movement.sampleTicks` ticks) exceeds `emergencyMinSpeed` (0.3, just above sprinting) it becomes `emergencyRadius + ceil(speed * emergencyLookaheadTicks / 16)`, capped at `emergencyMaxRadius`. Walking and sprinting keep the base radius; with lookahead 20 a horse (~0.6) gets +1, elytra (1.5–3) gets +2 to +4. The largest radius used in the interval is in the status log.
- **Warmup:** entries watched by a player who joined less than `warmupTicks` ago always load.
- **Save pause:** skipped on every 900th tick (autosave runs at the end of that tick, after worlds tick) and for `savePauseTicks` after any `saveAllWorlds` call. Emergency and warmup chunks still load.

## Session state

The integrated server restarts its tick counter at 0 every time a world is entered. `SessionState.reset` clears everything keyed on tick numbers or tied to one save (tick accounting, stage gate, populate queue, holds, preload, stage 2 and motion state, warmup, the known-generated cache, diagnostics counters). It runs at server start and stop, and from `TickMonitor.onTickStart` when the tick counter goes backwards or a different thread starts ticking; each run logs `session state reset (reason)`. `TickMonitor.ticksSince(then)` treats a tick number from the future (stale) as long ago, never as recent. Without this, a re-entered world held the save pause for about 95 s and the starvation guard never fired (DEVBOX log, 2026-10-17).

Note on the speed formula: the plain `base + ceil(speed * lookahead / 16)` is already `base + 1` at walking speed (0.22 b/t), so the `emergencyMinSpeed` floor is what keeps walking at today's value.

Do-not-regress: nothing here throttles chunks loaded by `World.getChunk` callers, ForgeChunkManager tickets, or structure lookups.
