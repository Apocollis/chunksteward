# Status log metrics

`logging.statusIntervalSeconds > 0` logs two lines every interval and turns on stage timing. With it at 0, every timing hook is one config read and a thread compare; nothing is recorded.

Line 1 (`TickMonitor`): tick EMA, non-chunk EMA, chunk-provision EMA; chunks generated, deferred and bypassed by the throttle; the largest emergency radius used; **waiting** (entries in `entriesWithoutChunks` without a chunk, all loaded worlds, sampled at log time); preloaded full and terrain-only chunks; then `StageGate`'s populate counts (`deferred N (cascade M), resumed R, cascades C`, `populate queue Q (drained D, dropped X)`), light deferred, drained and queued, and chunks currently held; then `preload idle N, moving M; yielded to demand/busy/populate backlog, minute limit`; then `random-tick guard N (sampled: class count ...)`.

Line 2 (`GenMetrics`): `stages: terrain X ms/N, populate X ms/N (world generators X ms), light X ms/N; wait avg X ms max X ms over N chunks; top generators: class X ms/N, ...`

- **Terrain:** around `IChunkGenerator.generateChunk` in `ChunkProviderServer.provideChunk` (all generation, not just the throttled path).
- **Populate:** `Chunk.populate(IChunkGenerator)` HEAD to RETURN, including the world generators (`N` = populate calls, which includes the cheap retrogen path for already-populated chunks).
- **Light:** `Chunk.checkLight()` from every caller (populate, `onTick`, the deferred-light drain).
- **World generators:** each `IWorldGenerator.generate` in Forge's `GameRegistry.generateWorld`, keyed by class, top `logging.topGenerators`.
- Times are exclusive. A stack charges each nanosecond to the innermost stage, so a cascade (populate → generate a neighbour → terrain) is not counted twice. A frame whose exit is skipped by an exception is unwound at the next matching exit or at tick start.
- Server thread only (the thread that runs `MinecraftServer.tick`). Client-side `checkLight` in single player is ignored.
- **Cascades:** a chunk generated while a populate is running, charged to the innermost running `IWorldGenerator` class: `cascades by generator: class N, ...` (top 3).
- **Wait:** time from `PlayerChunkMapEntry` construction to the first `providePlayerChunk` that returns true in `PlayerChunkMap.tick`. Entries whose chunk arrives through Forge's async disk load callback are not sampled. Entries are kept in a `WeakHashMap`, so removed entries are not retained.
