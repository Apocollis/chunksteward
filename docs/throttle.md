# Adaptive throttle

Hook: `PlayerChunkMap.tick` calls `providePlayerChunk(canGenerate)` for each entry whose chunk is missing. Vanilla stops at 50 ms or 49 chunks and always lets the first success through. `ChunkThrottle.provide` wraps that call. A `false` return keeps the entry queued for a later tick. The order in which entries are offered is set by [ordering.md](ordering.md).

- `canGenerate == false` (disk load only) and dimensions excluded by the dimension list pass straight through.
- **Busy signal:** `TickMonitor` keeps an EMA (alpha 0.1, about 20 ticks) of tick work *excluding* chunk provision, measured from `MinecraftServer.tick` HEAD to RETURN. A second EMA of chunk provision time (`chunkEmaMs`) is the "heavy generation" signal for light deferral.
- **Idle:** EMA below `idleTickMs` → full vanilla speed.
- **Measured TPS:** `min(20, 1000 / mean tick work over the last 100 ticks)`, about 5 s. Any mean at or under 50 ms reads 20; a slow stretch (a village, a dungeon step, a "Can't keep up!") pulls the mean above 50 and TPS below 20. Shown in the status log and `/chunksteward status`.
- **Load factor:** `clamp((TPS - tpsLoaded) / (tpsFull - tpsLoaded), 0, 1)`: 1 at `tpsFull` (default 20: the server has room), 0 at `tpsLoaded` (default 15: under load), linear in between.
- **Busy (credit):** each tick the server earns `(50 - EMA of non-chunk work) * load factor` ms of chunk credit, capped at about one and a half chunks' cost (scaled by the load factor). A chunk is admitted while the balance is positive and its real cost (every provided chunk, including emergency ones) is subtracted, so the balance can go negative and the next ticks pay it back (floor -200 ms). The average chunk work per tick equals the allowance even when one chunk costs more than a single tick's share (the old per-tick check admitted almost nothing when a chunk cost more than the allowance). At load factor 0 only the starvation guard admits chunks.
- **Starvation guard:** at least one generated chunk every `maxStarveTicks` ticks (default 4 = 0.2 s).
- **Emergency bypass:** an entry within the emergency radius of a watching player always loads. Radius per player:
  `emergencyRadius`, and once the player's horizontal speed (from `PlayerMotion`, blocks per tick) exceeds `emergencyMinSpeed` (0.3, just above sprinting) it becomes `emergencyRadius + ceil(speed * emergencyLookaheadTicks / 16)`, capped at `emergencyMaxRadius`. Walking and sprinting keep the base radius; with lookahead 20 a horse (~0.6) gets +1, elytra (1.5–3) gets +2 to +4. The largest radius used in the interval is in the status log.
- **Warmup:** entries watched by a player who joined less than `warmupTicks` ago always load.
- **Save pause:** skipped on every 900th tick (autosave runs at the end of that tick, after worlds tick) and for `savePauseTicks` after any `saveAllWorlds` call. Emergency and warmup chunks still load.

Note on the speed formula: the plain `base + ceil(speed * lookahead / 16)` is already `base + 1` at walking speed (0.22 b/t), so the `emergencyMinSpeed` floor is what keeps walking at today's value.

Do-not-regress: nothing here throttles chunks loaded by `World.getChunk` callers, ForgeChunkManager tickets, or structure lookups.
