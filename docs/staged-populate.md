# Staged populate

Populate (`Chunk.populate`, RTG's `LatePopulatingChunk.doPopulate`) is the largest cost of a new chunk and it writes the live world, so it cannot move to a worker as one call. Staged populate moves one piece of it, RTG's biome decorations (`IRealisticBiome.rDecorate`: trees, plants, and the rest of the realistic biomes' decorations), onto a terrain worker and applies the result on the server thread. It is off by default (`features.enabled`). It needs the terrain pipeline running with `rtg.world.gen.ChunkGeneratorRTG` in `pipeline.allowedGenerators`, and it only runs with the RTG-Plus 9.0.3 classes (see below).

## What runs where

For one chunk (cx, cz), in the order an unstaged populate uses:

1. Server thread: the opening light check, structures, lakes, lava, dungeons (all as before).
2. At `rDecorate`: the server thread reads the generator's `Random` (two copies), checks the chunk can be staged, and hands the decorations to the **feature lane** as a job. The rest of `populate` and `GameRegistry.generateWorld` are skipped for this chunk.
3. A terrain worker takes the job when no terrain job is waiting. One job runs at a time.
4. At tick end the server thread **applies** finished jobs in the order they were created: the recorded block writes through the real `World.setBlockState` (original flags), the recorded light checks, then the rest of that chunk's populate (animals, ice and snow, the post-populate event, the light fix), then the mod `IWorldGenerator`s (next section), then `markDirty`. Up to `features.promoteMs` per tick, at least one job.

## Mod generators

With `features.modGenerators` (default true) the mod `IWorldGenerator`s of a staged chunk run in runs through Forge's own `GameRegistry.generateWorld` loop, which a per-thread plan filters (`GeneratorPlan`, `MixinGameRegistry`). Forge's order and each generator's per-chunk seed are unchanged.

- A first pass over the loop, which runs nothing, lists which generators may go to a worker: all of them, except classes in `features.serverOnlyGenerators` and classes demoted this session.
- Consecutive generators with the same answer form a run. A run for a worker is a feature job with its own scope (same rules as the decorations: 4x4 read window, 2x2 write window, journal, abort on tile entities and the other calls above); the job's finish starts the next run. A run for the server thread runs inline between jobs. The chunk stays held until the last run is applied. If a job cannot be queued the run is done inline.
- A generator that aborts a job or throws on a worker gets a strike (`features.demoteAfterAborts`, default 3); at that count the class runs on the server thread for the rest of the session. The status line counts demotions and the log names the class once.
- Each generator class has a lock: a worker run and a server-thread run of the same class never overlap (the server thread waits for the end of that one generator call). Other threads that touch a generator's static state are not covered; list such a generator in `serverOnlyGenerators`.
- Also answered from the job's window, or aborting it: `ChunkProviderServer.getLoadedChunk`/`loadChunk`/`provideChunk`, and aborting: entity list queries (`getEntitiesWithinAABB` and the excluding variants), `getVillageCollection`, `getMapStorage`, `getPerWorldStorage`.
- Default server-only list: AstralSorcery, Thaumcraft, The Betweenlands, Recurrent Complex (generators that keep global state, save world data or place tile entities and entities).

The tail in step 4 is RTG-Plus 9.0.3's `populate` code after `rDecorate`, copied unchanged (`MixinChunkGeneratorRTGSplit`), because `populate` cannot be resumed mid-method.

## What the worker sees (`FeatureScope`)

While a job runs, `World` calls made on that thread are answered from the job's own view (`MixinWorldFeature`); nothing in a loaded chunk changes.

- **Reads** come from the job's own writes first, then from 16 chunk objects captured when the job was accepted (the 4x4 window cx-1..cx+2, cz-1..cz+2, which RTG already requires to exist). The shared chunk map is never read, and a chunk is never generated.
- **Writes** go to a journal (`FeatureJournal`): block, flags, in order. Writes are allowed only in the 2x2 chunks cx..cx+1, cz..cz+1 (RTG decorates around the chunk centre offset by 8 blocks).
- **Heights** (`getHeight`, `canSeeSky`, precipitation height, top solid block) are worked out from the same view with the vanilla height-map rule, so a decoration placed after a tree sees the tree. The worker reads the height array directly and never calls `Chunk.getHeightValue`; before the job is accepted the server thread reads every column of the window once, which makes Arcana Quest Tweaks replay any pending light work on the server thread.
- **Light checks** (`World.checkLight`) are recorded and replayed on the server thread.
- **Anything else aborts the job**: a read or write outside the window, a block with a tile entity, a direct `Chunk.setBlockState`, `spawnEntity`, `scheduleUpdate`, `setTileEntity`, `notifyNeighborsOfStateChange`. An aborted job is run inline on the server thread, with the same starting `Random` state, and gives the world the same blocks. The first 20 reasons are logged (`features.logAborts`).

## Ordering and holds

- A populate whose 4x4 window overlaps the window of a job that is not applied yet **waits** (`StageGate`, `PopulateQueue`). That keeps the order an unstaged populate has, where each chunk finishes before the next starts. The populate queue skips such chunks until the job is applied.
- Until a job is applied its chunk is **held**: `Chunk.isPopulated()` is false (so it is not sent), `queueUnload` is ignored, and the chunk is saved as not terrain populated and not light populated (so a crash loads it as unpopulated, not as a chunk without its decorations).
- A job that cannot finish is run inline: no workers (`pipeline` off, killed, worker cap 0) or longer than `features.maxWaitSeconds`. Dimension unload and server stop apply every job first.
- The decoration objects (trees and the rest) keep per-call state and are shared. A lock (`FeatureLane.decoLock`) makes a worker job and an inline decoration exclusive. It is only taken while staging is available.

## Random state

The job runs on a copy of the generator's `Random`. When it ends, the server thread sets the generator's `Random` to the state the job ended in before running the tail, so animals draw what they would have drawn. `Random` exposes no state, so `FeatureRandom` reads it from the serialized form and writes it back with `setSeed` (a pending Gaussian cannot be carried over; such a job is redone inline).

## RTG build guard

The staged tail is a copy of RTG-Plus 9.0.3. Staging runs only when the SHA-256 of `ChunkGeneratorRTG.class` and `LatePopulatingChunk.class` are those of 9.0.3 or are listed in `features.extraRtgClassHashes`. Otherwise staging is off and the log says so. Nothing else changes.

## Light (Arcana Quest Tweaks)

`ChunkStewardAPI.registerPopulateScope` (version 4) lets a mod bracket the work Chunk Steward finishes after the populate call returned. Arcana Quest Tweaks registers one so its populate relight records the applied writes and the tail the way it records an unstaged populate.

## Config (`features`)

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | false | Stage RTG's decorations. |
| `maxQueued` | 8 | Chunks waiting for their staged work. Past this a populate runs inline. |
| `promoteMs` | 6 | Milliseconds per tick for applying finished jobs (at least one). |
| `maxWaitSeconds` | 10 | A job older than this is run inline. |
| `logAborts` | true | Log why a job fell back to the server thread, once per reason and mod class. |
| `modGenerators` | true | Run the mod `IWorldGenerator`s of staged chunks on a worker too (with `enabled`). |
| `serverOnlyGenerators` | AstralSorcery, Thaumcraft, Betweenlands, Recurrent Complex | Generator classes (full name, or package prefix ending in a dot) that always run on the server thread. |
| `demoteAfterAborts` | 3 | Aborts or errors in one generator class before it is demoted for the session. |
| `extraRtgClassHashes` | empty | SHA-256 of RTG classes the staged tail may also run with. |

The status log has a `staged populate:` line each interval: queued, submitted, on worker, applied, inline by reason (tile entity, outside window, unsupported, timeout, error), not staged by reason (queue full, chunk not loaded, busy), mod generators staged / inline / demoted, average worker and wait time, and since the start of the session the mod classes behind the aborts and the worker time by generator. A job counts as submitted once per run, so a chunk with mod generators submits two or more. The Forge event bus logs an abort that passes through an event listener as an error with a stack trace; those lines are dropped (`FeatureLogFilter`), the abort is counted here.

## Not covered

- RTG's decorations and the mod `IWorldGenerator`s are staged. Structure paste, lakes, dungeons, animals and ice and snow run on the server thread.
- A decoration that does something the list above aborts on is run inline every time; mod generators are demoted after repeated aborts (above), decorations are not.
- A populate that runs outside the queue (for example a mod that calls `Chunk.populate` itself) can still be staged, but a populate that is declined (queue full, chunk not loaded) runs everything inline.
- A job that aborts after some generators ran on the worker runs the whole run again inline from Forge's seed. The worker's writes were only journaled, so the world is the same, but a generator that changes something else (static state, saved data) on a worker does it twice. List such a generator in `serverOnlyGenerators`.
- Reads of a loaded chunk that another server activity changes during a job (liquid flow in an already-sent neighbour, for one) are not locked; a torn read fails the job and it runs inline.
