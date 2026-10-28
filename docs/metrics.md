# Status log metrics

`logging.statusIntervalSeconds > 0` logs two lines every interval and turns on stage timing. With it at 0, every timing hook is one config read and a thread compare; nothing is recorded.

Line 1 (`TickMonitor`, ends with `TPS x (load factor y, admission factor z)`, see [throttle.md](throttle.md)): tick EMA, non-chunk EMA, chunk-provision EMA; chunks generated, deferred and bypassed by the throttle; the largest emergency radius used; **waiting** (entries in `entriesWithoutChunks` without a chunk, all loaded worlds, sampled at log time); preloaded full and terrain-only chunks; then `StageGate`'s populate counts (`deferred N (cascade M), resumed R, ticket-exempt T, mod-ticket generation G, cascades C`; G = chunks generated on a `preload.busyIgnoreMods` ticket, not counted as player demand; `populate queue Q (drained D, dropped X)`), light deferred, drained and queued, and chunks currently held; then `preload idle N, moving M; yielded to demand/busy/populate backlog, minute limit`; then `stage2 hotspots N, generated G, populated P, yielded Y (last: reason), total T`; then `random-tick guard N (sampled: class count ...)`.

Line 2 (`GenMetrics`): `stages: terrain X ms/N, populate X ms/N (world generators X ms), light X ms/N; wait avg X ms max X ms over N chunks; top generators: class X ms/N, ...`

- **Terrain:** around `IChunkGenerator.generateChunk` in `ChunkProviderServer.provideChunk` (all generation, not just the throttled path). Reported as `terrain X ms/N (map-gens M ms)`, with `map-gens: Class X ms/N` from `MapGenBase.generate` by runtime class (caves, ravines, structure layout, including the village layout).
- **Populate:** `Chunk.populate(IChunkGenerator)` HEAD to RETURN (`N` = populate calls, which includes the cheap retrogen path for already-populated chunks). Reported as `populate X ms/N (generator populate G ms, structures S ms, world generators W ms, other O ms)`: *generator populate* is the chunk generator's own `populate(II)` call (RTG and vanilla decoration, plus structure paste not otherwise named); *structures* is `MapGenStructure.generateStructure` by runtime class (villages, mineshafts, strongholds, other mods' subclasses), listed as `structures: Class X ms/N`; *world generators* is the `IWorldGenerator` loop; *other* is what is left of the stage (Forge's loop overhead, event handling). The parts add up to the stage total.
- **Light:** `Chunk.checkLight()` from every caller (populate, `onTick`, the deferred-light drain).
- **World generators:** each `IWorldGenerator.generate` in Forge's `GameRegistry.generateWorld`, keyed by class, top `logging.topGenerators`.
- Times are exclusive. A stack charges each nanosecond to the innermost stage, so a cascade (populate → generate a neighbour → terrain) is not counted twice. A frame whose exit is skipped by an exception is unwound at the next matching exit or at tick start.
- Server thread only (the thread that runs `MinecraftServer.tick`). Client-side `checkLight` in single player is ignored.
- **Cascades:** a chunk generated while a populate is running, charged to the innermost running `IWorldGenerator` class: `cascades by generator: class N, ...` (top 3).
- **Wait:** time from `PlayerChunkMapEntry` construction to the first `providePlayerChunk` that returns true in `PlayerChunkMap.tick`. Entries whose chunk arrives through Forge's async disk load callback are not sampled. Entries are kept in a `WeakHashMap`, so removed entries are not retained.

## Pipeline and staged populate lines

When the terrain pipeline runs, a third line follows (`PipelineStats`):

`status: pipeline: workers N (cap C), queued Q, in flight F, ready R; terrain jobs J (P for players), worker avg X ms max Y ms; busy #0 terrain A% staged B% | #1 ...; waited to start avg ... ms max ... ms, ready before install avg ... ms max ... ms; installed I, stolen back S, discarded D, failed X, generated on server thread G`

- `queued`/`in flight`/`ready` are the table's state when the line is written: jobs not yet claimed, being generated (by a worker or claimed by the server thread), and finished but not installed.
- `busy` per worker: share of the interval spent on terrain jobs and on staged populate jobs. A worker well under 100% in total while `queued` is large means the workers are not the limit (the CPU guard cap, the ranking window `pipeline.maxInFlight`, or `pipeline.maxReady` is).
- `waited to start`: queue time before a worker claimed a terrain job. `ready before install`: how long a finished chunk waited for the server thread to take it. A large second number means the throttle, not the workers, is slow.
- `player chunks`: what the player-chunk provision did with the chunks players wait for: finished worker chunks installed, urgent (emergency radius or login warmup) generated at once, starved (one per `throttle.maxStarveTicks` when none was admitted); and what it deferred: not finished (a worker has not produced it), headroom (finished, but the tick has no room), saving, loading.
- `stolen back` and `generated on server thread`: chunks the server thread had to generate itself because no worker had started them, or a running one took too long (`pipeline.stealWaitMs`).

With `features.enabled` a `staged populate:` line follows; see [staged-populate.md](staged-populate.md).

`logging.pipelineTrace = true` also writes `logs/chunksteward-trace.csv` (`ms,event,dimension,x,z,detail`): `claim` (worker slot, queue wait), `done` or `fail` (worker time), `install` (ready wait), `server` (generated on the server thread, and why), `feature_submit`, `feature_apply` (wait, worker time, ok or why it ran inline). Sort by chunk to follow one chunk through the pipeline. Capped at 400000 lines per session; events are dropped, not delayed, when the writer is behind.

## RTG-Plus

When RTG-Plus chunks populate, the populate line ends with `rtg windows N (nested cancelled M)`: N outer passes admitted, M re-entrant passes cancelled. Before RTG-Plus support the populate stage showed `0.0 ms/0` on RTG terrain. The generator-populate split (generator populate / structures / world generators / other) works as for vanilla chunks.
