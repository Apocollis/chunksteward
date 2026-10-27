# Terrain pipeline

A dimension's own chunk generator can run on worker threads after a verify pass for that exact class. With the default empty `pipeline.allowedGenerators` list, no worker threads start, the CPU guard does not sample, and chunk loading is unchanged.

A class generates off-thread only in a dimension where `/chunksteward pipeline verify 10000` finished clean. The pass is stored in `chunksteward-pipeline-passes.txt` in the overworld save. A parent class does not stand in for a subclass. RTG's copy still shares `RTGWorld`, so a verify mismatch leaves it on the server thread.

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
- that class has a recorded pass for the dimension
- the CPU-guard cap is above 0
- LoliASM `makeEventsSingletons` is not true

Listing a class before a pass starts the workers (`max(1, min(2, available processors - 2))`, priority one below normal). They park until a pass exists. Ordinary player chunks in a passed dimension wait for a worker result. Emergency chunks, login warmup, and the starvation guard still generate on the server thread. A chunk Forge is already loading is not submitted. Preload and stage 2 are unchanged.

Workers skip structure layout, except Atum pyramids: Arcana Quest Tweaks flattens the ground around a pyramid inside `generateChunk`, so the start has to exist then. Structure data a worker or verify leg saves goes to scratch storage, not the world's. The server thread calls `recreateStructures` on the installed chunk before vanilla continues into populate. Cap 0 stops deferring and uses the previous path.

While verify pauses a dimension, stage 2 and idle preload skip players in it (`TerrainPipeline.isPaused`).

## Shared state on worker threads

Each worker has its own generator and biome provider copy. State those copies still share is made per thread:

- `IntCache` (vanilla genlayers): one pool per thread.
- `World.getBiomeProvider` / `WorldProvider.getBiomeProvider`: return the job's copy only when the world's dimension is the job's.
- Nether API's NetherEx biomes: `setSeaLevel` goes to a per-thread value for that world, restored on exit.
- End Expansion's Barrend biome: uses the generator's random.
- Aurorian: the generator random is reseeded from the chunk position.
- Betweenlands: biome noise buffers per thread.
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
| `allowedGenerators` | empty | Full class names. Empty means no workers. |
| `speculative` | true | Not used yet. Preload and stage 2 stay on the server thread. |
| `readyTtlSeconds` | 120 | Reserved for a later phase. |
| `cpuGuard` | `thread` | `thread`, `system`, or `off`. Samples only after a verify pass exists. |
| `cpuGuardRatio` | 0.85 | `thread`: server-thread CPU time over tick wall time, below this. |
| `cpuGuardLoad` | 0.90 | `system`: host load above this. |
| `cpuGuardSeconds` | 3 | How long the signal stays past the line before it counts. |
| `cpuGuardResumeSeconds` | 10 | Minimum time between worker-cap steps. |

The thread ratio (CPU time over that tick's wall time) is smoothed per tick, alpha 0.02, about 100 ticks, and judged once a second of tick time. Save-pause ticks are not samples. While the smoothed signal stays hot the cap steps down by one, to 0. At 0, if it is still hot 10 s later, the cap is restored and the guard is ignored for 5 minutes. A clear signal raises the cap by one step. Cap 0 does not defer loading.

## Command

`/chunksteward pipeline status|off|on` and `pipeline verify <count> [dimension]`. `off` and `on` last for the session. Verify runs off the server thread, over chunks around 30000, 30000, in two orders. A clean count of at least 10000 records the pass. The periodic status log is unchanged. `/chunksteward status` includes the pipeline line.

## Not in this build

No speculative preload or stage 2, no API version change (`ChunkStewardAPI` stays at 2). A mismatch or a generator with no copy stays on the server thread. RTG is copied for verify; the shared `RTGWorld` is unchanged.
