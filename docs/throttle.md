# Adaptive throttle

Hook: `PlayerChunkMap.tick` calls `providePlayerChunk(canGenerate)` for each entry whose chunk is missing. Vanilla stops at 50 ms or 49 chunks and always lets the first success through. `ChunkThrottle.provide` wraps that call. A `false` return keeps the entry queued for a later tick. The order in which entries are offered is set by [ordering.md](ordering.md).

- `canGenerate == false` (disk load only) and dimensions excluded by the dimension list pass straight through.
- **Busy signal:** `TickMonitor` keeps an EMA (alpha 0.1, about 20 ticks) of tick work *excluding* chunk provision, measured from `MinecraftServer.tick` HEAD to RETURN. A second EMA of chunk provision time (`chunkEmaMs`) is the "heavy generation" signal for light deferral.
- **Idle:** EMA below `idleTickMs` → full vanilla speed.
- **Busy:** a chunk may generate only if `chunk time this tick + EMA cost of one chunk <= targetTickMs - EMA`.
- **Starvation guard:** at least one generated chunk every `maxStarveTicks` ticks (default 4 = 0.2 s).
- **Emergency bypass:** an entry within the emergency radius of a watching player always loads. Radius per player:
  `emergencyRadius`, and once the player's horizontal speed (from `PlayerMotion`, blocks per tick) exceeds `emergencyMinSpeed` (0.3, just above sprinting) it becomes `emergencyRadius + ceil(speed * emergencyLookaheadTicks / 16)`, capped at `emergencyMaxRadius`. Walking and sprinting keep the base radius; with lookahead 20 a horse (~0.6) gets +1, elytra (1.5–3) gets +2 to +4. The largest radius used in the interval is in the status log.
- **Warmup:** entries watched by a player who joined less than `warmupTicks` ago always load.
- **Save pause:** skipped on every 900th tick (autosave runs at the end of that tick, after worlds tick) and for `savePauseTicks` after any `saveAllWorlds` call. Emergency and warmup chunks still load.

Note on the speed formula: the plain `base + ceil(speed * lookahead / 16)` is already `base + 1` at walking speed (0.22 b/t), so the `emergencyMinSpeed` floor is what keeps walking at today's value.

Do-not-regress: nothing here throttles chunks loaded by `World.getChunk` callers, ForgeChunkManager tickets, or structure lookups.
