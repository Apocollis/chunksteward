# Terrain pipeline

A dimension's own chunk generator can run on worker threads when its exact class is listed in `pipeline.allowedGenerators`. With the default empty list, no worker threads start, the CPU guard does not sample, and chunk loading is unchanged.

A parent class does not stand in for a subclass. A listed class runs in every dimension that uses it. `/chunksteward pipeline verify 10000` is how a class is checked before it is trusted; a clean run adds that class to the list and records the pass in `chunksteward-pipeline-passes.txt`. A mismatch holds that class on the server thread until the next launch. On a worker or verify thread, RTG uses that generator's `Random`, locks each terrain `generateNoise` and surface `paintTerrain`, and keeps a private cellular cache. The server thread still uses the shared `RTGWorld`.

Classes verify can build:

| Dimension | Class |
| --- | --- |
| Vanilla nether / end | `net.minecraft.world.gen.ChunkGeneratorHell`, `ChunkGeneratorEnd` |
| Nether (this pack) | `logictechcorp.netherex.world.generation.ChunkGeneratorNetherEx` |
| Nether API nether | `git.jbredwards.nether_api.mod.common.world.gen.ChunkGeneratorNether` |
| End (this pack) | `git.jbredwards.nether_api.mod.common.world.gen.ChunkGeneratorTheEnd` |
| Aurorian | `com.shiroroku.theaurorian.World.AurorianChunkGenerator` |
| Atum | `com.teammetallurgy.atum.world.ChunkGeneratorAtum` |
| Twilight Forest | `twilightforest.world.ChunkGeneratorTwilightForest` or `ChunkGeneratorTwilightVoid` |
| Aether | `com.gildedgames.the_aether.world.ChunkProviderAether` |
| The Beneath | `com.shinoow.beneath.common.world.gen.ChunkProviderDeepDank` |
| Overworld (RTG) | `rtg.world.gen.ChunkGeneratorRTG` |
| Betweenlands | `thebetweenlands.common.world.gen.ChunkGeneratorBetweenlands` |
| Emptiness | `thecodex6824.thaumicaugmentation.common.world.ChunkGeneratorEmptiness` |

End Expansion does not have a generator. Its biomes are part of `ChunkGeneratorTheEnd`. Its islands and fortresses are placed during populate, which stays on the server thread.

## When it generates

All of these are required:

- `pipeline.enabled` (default true)
- the session kill switch is clear (`/chunksteward pipeline off` sets it; `on` clears it; a session reset clears it)
- the generator's class name is in `allowedGenerators`
- the CPU-guard cap is above 0
- LoliASM `makeEventsSingletons` is not true

Listing a class starts the workers (`max(1, min(2, available processors - 2))`, priority one below normal). Ordinary player chunks for a listed class wait for a worker result. Emergency chunks, login warmup, and the starvation guard still generate on the server thread. A chunk Forge is already loading is not submitted.

With `pipeline.speculative` (default true), idle preload and the stage 2 terrain phase queue that chunk behind every player chunk in the ranking window. They call `provideChunk` only to install a finished result, still inside the existing terrain-only or preload scope. A result that is not ready yet is put back and tried on a later tick. Player chunks fill the window first. When the ready map is full, the oldest speculative result is dropped so a player chunk can take the slot. Stage 2's populate phase still loads and populates on the server thread. `readyTtlSeconds` is still unused.

Workers skip structure layout, except Atum pyramids: Arcana Quest Tweaks flattens the ground around a pyramid inside `generateChunk`, so the start has to exist then. Structure data a worker or verify leg saves goes to scratch storage, not the world's. The server thread calls `recreateStructures` on the installed chunk before vanilla continues into populate. Cap 0 stops deferring and uses the previous path.

While verify pauses a dimension, stage 2 and idle preload skip players in it (`TerrainPipeline.isPaused`).

## Shared state on worker threads

Each worker has its own generator and biome provider copy. State those copies still share is made per thread:

- `IntCache` (vanilla genlayers): one pool per thread.
- `World.getBiomeProvider` / `WorldProvider.getBiomeProvider`: return the job's copy only when the world's dimension is the job's.
- Nether API's NetherEx biomes: `setSeaLevel` goes to a per-thread value for that world, restored on exit.
- End Expansion's Barrend biome: uses the generator's random.
- Aurorian: the generator random is reseeded from the chunk position.
- `World.setRandomSeed`: on a worker or verify thread it seeds a per-thread `Random` (vanilla formula) instead of the shared `World.rand`, so structure spawn checks (Emptiness spires, end cities) do not race.
- Nether API's end: `recreateStructures` also generates the end city starts, as vanilla's does.
- Betweenlands: biome noise buffers per thread (reads redirected; the array assignment stays, see `MixinBiomeGenerator`).
- Twilight Forest highlands: the column's top and filler block are held per thread; the shared biome fields stay at their defaults.
- Atum: the chunk keeps the 16×16 surface biomes. Verify snapshots Tweaks' pyramid plates and removes or reverts the ones the run added inside its square (plus 256 blocks), leaving plates the server thread registered elsewhere.

## Config (`pipeline`)

| Key | Default | Role |
| --- | --- | --- |
| `enabled` | true | Master switch. Idle while the generator list is empty. |
| `workers` | 0 | 0 uses the formula above. A set value is clamped to 1 or 2. |
| `maxInFlight` | 16 | Ranking window. |
| `maxReady` | 64 | Cap on results waiting to install. |
| `stealWaitMs` | 10 | How long the server thread waits on a worker that already claimed the chunk. |
| `jobTimeoutSeconds` | 30 | Abandons a claimed job. The thread is retired after it returns. |
| `maxFailures` | 3 | Failures of one class before that class stays on the server thread until restart. |
| `verifyOnStart` | false | Not used. Verify is the command below. |
| `allowedGenerators` | empty | Full class names. Empty means no workers. A listed class runs in every dimension that uses it. A clean verify of 10000 chunks adds the class. |
| `speculative` | true | Preload and stage 2 terrain queue behind player chunks and install a finished result. Off keeps those calls on the server thread. |
| `readyTtlSeconds` | 120 | Reserved for a later phase. |
| `cpuGuard` | `thread` | `thread`, `system`, or `off`. Samples only while a generator class is listed. |
| `cpuGuardRatio` | 0.85 | `thread`: server-thread CPU time over tick wall time, below this. |
| `cpuGuardLoad` | 0.90 | `system`: host load above this. |
| `cpuGuardSeconds` | 3 | How long the signal stays past the line before it counts. |
| `cpuGuardResumeSeconds` | 10 | Minimum time between worker-cap steps. |

The thread ratio (CPU time over that tick's wall time) is smoothed per tick, alpha 0.02, about 100 ticks, and judged once a second of tick time. Save-pause ticks are not samples. While the smoothed signal stays hot the cap steps down by one, to 0. At 0, if it is still hot 10 s later, the cap is restored and the guard is ignored for 5 minutes. A clear signal raises the cap by one step. Cap 0 does not defer loading.

## Command

`/chunksteward pipeline status|off|on` and `pipeline verify <count> [dimension]`. `off` and `on` last for the session. Verify runs off the server thread, over chunks around 30000, 30000, in two orders. A clean count of at least 10000 records the pass and adds that class to `allowedGenerators`. The player who started it sees `chunk x of n` on the action bar, then pass or fail in chat. `x` is how many chunks the slower of the two legs has finished. The periodic status log is unchanged. `/chunksteward status` includes the pipeline line.

## Not in this build

`readyTtlSeconds` does not expire a waiting result. A mismatch or a generator with no copy stays on the server thread. RTG is copied for verify. A worker or verify thread does not share `rand()`, the terrain and surface fields those calls write, or the cellular cache.

## Biome snapshots

A terrain worker used to generate from its own copy of the dimension's biome provider. A mod that builds the biome layout from layers with shared state (Geographicraft / Climate Control decides the biome layout and land masses; RTG-Plus shapes the terrain inside that map) can make that copy answer differently from the server's provider, and the chunk then has biomes, and terrain shaped by them, that do not match its neighbours.

With `pipeline.biomeSnapshot` (default true) the server thread asks its own biome provider for the biomes around each queued chunk (`pipeline.biomeSnapshotRadius`, default 80 blocks, one entry per block; RTG-Plus reads -64 to +100 blocks around the chunk origin), within `pipeline.biomeSnapshotMs` per tick (default 3, at least one snapshot), and the worker generates from that snapshot (`SnapshotBiomeProvider`): single-block and rectangle lookups come from it, so the worker builds no biome tree of its own and cannot see a different layout. A worker does not start a job until its snapshot exists. A lookup outside the snapshot (a far structure check, the generation layer) is answered by the worker's own copy and counted: `biome snapshots N (avg X ms on the server thread, M lookups outside one)` in the `pipeline:` status line.

Guardrails: after a worker generates a chunk it records what its provider (the snapshot) answers at 64 points; the server compares with its own provider when it installs the chunk, and a chunk that differs is discarded and generated on the server thread (`pipeline.biomeCheck`, default true; logged as `pipeline biome mismatch`). When a worker copy is built, it is compared with the server's provider over a 24x24 grid spread across +-6000 x +-12000 blocks; with snapshots on a difference is only logged, with `biomeSnapshot` off the generator stays on the server thread.

## Depths Update on the workers

Depths Update wraps the generator call in `ChunkProviderServer.provideChunk` with a handler that, after the generator returns the chunk, fills the deep band (deepslate and bedrock), carves caves and underground rivers, and rebuilds the sky light map. That is a few percent of the server thread for every new chunk (about 5% in the profiles). A worker calls the generator directly, so none of it ran there.

With `pipeline.depthsPass` (default true) the worker runs it (`DepthsPass`, a copy of the handler's code after the generator call, with noise objects of its own per worker slot) and the server skips Depths' handler for a chunk a worker finished: a mixin at the head of that handler (`MixinDepthsProvide`, late config `mixins.chunksteward.depths.json`, queued only when the Depths Update jar is present) installs the finished chunk through the generator and returns it. The skip is required, not optional: the deep fill would overwrite the carved caves below Y 0 if it ran again.

It switches on only when all of these hold, decided once and logged: Depths Update is loaded; the handler class is the build the copy was written against (1.12.2-1.0.0-a12, SHA-256 in `DepthsBridge`, or listed in `pipeline.extraDepthsClassHashes`); and the skip handler is present on `ChunkProviderServer` (the injection is not required, so a failure to apply only leaves Depths on the server thread). If the worker pass throws, the job fails and the server generates the chunk itself. If an install falls back to the server thread at the last moment, Depths runs as usual on that chunk.
