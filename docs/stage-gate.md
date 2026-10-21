# Stage gate

`server/StageGate` is the one place that answers "run this generation stage for this chunk now, or defer it". Mixins call `StageGate.runNow(Stage, Chunk)`. `false` means the caller skips the stage, and StageGate owns the follow-up, so no deferred work is lost.

| Stage | Hook | Deferred when | Follow-up |
| --- | --- | --- | --- |
| `POPULATE` | `Chunk.populate(IChunkGenerator)` (`func_186034_a`) HEAD, cancel | a terrain-only preload chunk while preload generates; a cascade chunk; any populate past the tick budget unless near a player ([populate.md](populate.md)) | `PopulateQueue`, drained each tick within a budget. Terrain-only chunks: none while unloaded (vanilla populates on a later load); claimed by a player while loaded → queued |
| `LIGHT` | `Chunk.checkLight()` (`func_150809_p`) HEAD, cancel, when the chunk is terrain-populated and not light-populated | `light.deferEnabled`, heavy generation, chunk away from players and outside their direction | per-dimension queue, drained within a per-tick budget |

## Populate (terrain-only ring)

`func_186034_a` is the single choke point: `Chunk.populate(IChunkProvider, IChunkGenerator)` calls it for the chunk and up to three neighbours, and it is where `checkLight`, `generator.populate`, `GameRegistry.generateWorld` and Forge's cascade log run.

Why suppression covers every tracked terrain-only chunk and not just the one being generated: vanilla populates chunk A when the last of A, A+x, A+z, A+x+z loads. Generating the next ring chunk would otherwise populate its neighbour right away.

Why it is limited to preload's own `provideChunk` calls (`preloading`): when a player or anything else loads chunks, vanilla's populate must run as usual. A terrain-only chunk that is still loaded when a player's chunk map claims it would otherwise never populate (no load event left to trigger it) and never be sent (`isPopulated` is false), leaving a hole. `StageGate` keeps the tracked set per dimension and checks it every tick END. It drops entries that are unloaded or populated. For an entry that `PlayerChunkMap.contains`, it is queued in `PopulateQueue`, whose drain runs `chunk.populate(provider, provider.chunkGenerator)`, the same call a load would have made.

Populate deferral of normal chunks (budget and cascades) is in [populate.md](populate.md); it uses the same gate and the same queue.

## Light deferral (default off)

`Chunk.onTick` retries `checkLight()` every tick while `!isLightPopulated && isTerrainPopulated`. That happens at the edge of loaded terrain, where the light pass needs neighbours that are not loaded yet. The populate-time `checkLight()` is left alone because generators read light while populating. `World.checkLight` is not touched.

The gate sits at `checkLight()` HEAD and only looks at calls on chunks that are terrain-populated but unlit. Populate's call is excluded automatically, because populate calls `checkLight()` while `isTerrainPopulated` is still false. It does not hook `onTick`. In this pack, Somnia's coremod rewrites the `onTick` call into `SomniaUtil.chunkLightCheck(Chunk)`, and Arcana Quest Tweaks overwrites that with its own throttle: at most once per tick, and every 20 ticks for chunks more than 2 chunks from an awake player. The two stack: Tweaks decides whether to retry, then StageGate decides whether the retry runs now. StageGate's own drain bypasses the gate, and so do other threads (client chunks in single player).

A retry is deferred when all of these hold:
- `light.deferEnabled`;
- server world, dimension in the dimension list;
- generation is heavy: `TickMonitor.chunkEmaMs >= heavyGenMs` (only checked for chunks not yet queued);
- not within `protectRadius` (2) chunks of any player in that world (square) and not inside the 60 degree cone of any player's direction (`PlayerMotion`);
- the queue holds fewer than `maxQueued` entries.

A deferred chunk is queued once (position only, de-duplicated). While queued, its onTick retries keep deferring unless a player comes near or turns toward it, and then it is lit at once. Each server tick END, the queue drains up to `drainPerTick` chunks within `drainBudgetMs`, calling `checkLight()` on chunks still loaded and still unlit. The drain keeps running after deferral is switched off, until the queue is empty.

Effect: the chunk stays `isLightPopulated = false`, so `isPopulated` is false and `PlayerChunkMapEntry.sendToPlayers` does not send it until the queued pass runs. Nothing is shown unlit; delivery is delayed.

Unload/save while queued: only `(dimension, x, z)` is queued, never the `Chunk`. An unloaded chunk is saved with `LightPopulated = false` and dropped from the queue when drained; vanilla relights it after the next load. World unload clears that dimension's queue. Nothing leaks: the queue is bounded by `maxQueued` and each entry leaves on its drain.

## Extending

Add a stage (or a policy inside `POPULATE`) with its own queue. The pattern is `DimQueue` and `PopulateQueue`: a position set per dimension, drained by budget, with only positions stored. Neighbour tracking belongs in StageGate next to the terrain-only set. Mixins keep calling `runNow` unchanged.

## RTG-Plus windows

`StageGate` keeps a small stack of RTG windows beside the populate depth frames (`rtgOuterAllow`, `rtgOuterExit`, `rtgMemberAllow`). The stack is cleared at tick start and on `SessionState.reset`, so a throw cannot leave it open. See [populate.md](populate.md#rtg-plus-latepopulatingchunk) for the rules.
