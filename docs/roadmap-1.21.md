# Roadmap: bringing staged chunk generation closer to 1.21

Handoff for a fresh session. Everything here is on branch `experimental`; the last commit when this was written was `3b9a8a0` plus this file. Read `docs/pipeline.md`, `docs/staged-populate.md`, `docs/staged-light.md`, `docs/stage2.md` and `docs/metrics.md` for the details of what exists.

## Goal

Generate 1.12.2 / Cleanroom chunks the way 1.21 does: stages that run on worker threads against private data, with the main thread doing only a cheap final commit. The target is realistic parity, not exact: most chunks (90 percent or more) go through the off-thread path with a cheap commit, and the rest fall back to inline. Full parity is not reachable on 1.12.2 because other mods' generators touch the live world.

## What exists (and where)

| 1.21 stage | Here |
|---|---|
| biomes | `BiomeSnapshot` taken on the server thread, workers generate from `SnapshotBiomeProvider` |
| noise, surface, carvers | `TerrainPipeline` workers (`chunksteward-terrain-N`, cap 2), `PipelineTable`/`PipelineJob`, `GenSource`; Depths Update's fill, caves and rivers via `DepthsBridge`/`DepthsPass` |
| structure starts | inside terrain generation (map-gens); structure placement during populate still starts on the server thread |
| features | `FeatureLane` runs `FeatureJob`s on workers: RTG decorations (`RtgFeatures`, `RtgDecorationStage`, `MixinChunkGeneratorRTGSplit`, a copy of the RTG-Plus 9.0.3 tail guarded by class hashes) and eligible mod `IWorldGenerator`s (`GeneratorChain`, `GeneratorPlan`, `GeneratorPolicy`). Writes go to a `FeatureJournal` through `FeatureScope` (2x2 write window, 4x4 read window, `FeatureWindow`). Unsupported calls abort the job and it runs inline |
| light | `LightEngine` (pure Java, tested against a brute-force fixed point), `LightAdapter`, `LightStage` (worker computes sky and block nibble arrays and heights; server installs them), `LightVerify`, `LightAudit` (`/chunksteward light fix`), precipitation heights installed from the worker. Arcana Quest Tweaks drops its deferred relight through `PopulateScope.lightInstalled` (API v5) |
| spawn | still inside the populate tail on the server thread |
| full (commit) | `FeatureLane.applyJob` on the server thread at tick end: replays the journal with `World.setBlockState`, installs light, runs the RTG tail and the server-only generators |
| scheduling | `TerrainPipeline.rank()` ranks player chunks, then idle preload, then stage 2; `ChunkThrottle` admits installs; `PopulateQueue`/`StageGate` pace populates; `IdlePreload`, `Stage2Pregen` and `Hotspots` for pregen |

Server-only generators (stay inline): Astral Sorcery, Thaumcraft, The Betweenlands, Recurrent Complex (`features.serverOnlyGenerators`). A generator that aborts three times is demoted for the session (`features.demoteAfterAborts`).

## Measured baselines (user's pack, one flight)

- A worker finishes a terrain chunk in about 23 ms (median); a staged populate job is submitted to applied in about 100 ms (median).
- Installs about 10 chunks per second before the populate drain fix, about 25 per second after it.
- Server thread, in flight, as a share of the thread: entity updates 15 to 20 percent, mob spawner about 20 before the pause (In Control about half of it, see the Tweaks spawning work), staged apply 12 to 14 percent, chunk provide and install 8 to 11 percent, Arcana Quest Tweaks relight replay 4.5 to 8.5 percent (before the boundary-check filter), post-world-tick events in total about 11 percent.
- Inline fallbacks after the Soot sulfur ore fix: about 5 percent of populates (`getPerWorldStorage`, `getMapStorage`, `spawnEntity`, a few tile entity blocks).
- Main-thread commit is therefore the remaining chunk-side cost. That is what the roadmap attacks.

## Roadmap (in order of value for effort)

### 0. Test what is built (task #14 in the session task list)
Before more code: run the checklist in task #14 (clean start, discard breakdown, teleport recovery, `light verify` precipitation line, spawner pause, stage 2 pacing, index hook, grass colour). Decide which switches become defaults (`features.lightStage`, `spawner.pauseWhileFast`, `features.enabled`).

### 1. Cheaper commit: bulk-write plain blocks
- In `FeatureLane.replay`, classify each journaled write once: no tile entity on old or new state, no `onBlockAdded`/neighbour side effects that matter, flags show no update needed. Write those straight into the `ExtendedBlockStorage` sections, then do heights, light and client resend in one batch per section. Keep `World.setBlockState` for everything else.
- Expected: 30 to 50 percent less replay cost (about 2.6 percent of the thread now, plus a share of `markAndNotifyBlock`).
- Risks: blocks that rely on `onBlockAdded` (fire, redstone, fluids), tile entity creation, Depths' extended storage index mapping, and the Tweaks deferred relight recording `relightBlock` calls during the scope.
- Done when: a replay-equivalence test (same world state before and after) passes on recorded journals, and a profile shows the `FeatureLane.replay` row shrunk.

### 2. Optimistic section swap (the 1.21-style promotion)
- Run the job against private copies of the 2x2 write window. At commit, if no light-relevant block change happened in those chunks since the job started (use the `ChunkChanges` counter from `MixinChunkFeatureHold`, which counts writes that change opacity or emission), swap the sections in, install heights and light, resend changed sections (the `LightAudit.resend` trick sets `PlayerChunkMapEntry.changedSectionFilter` by storage index and `changes` to 64). If the counters moved, fall back to today's journal replay.
- Needs: a copy-on-read window for the worker (`FeatureScope` already presents a window), tile entities and entity lists handled at commit, and a decision for chunks players can already see.
- Expected: the commit becomes a swap plus a resend instead of thousands of `setBlockState` calls. Large effort and real risk; build it behind a config switch and verify on the user's world first.
- Done when: staged applies drop to about 1 ms in the `staged populate` status line, with no visible differences in `light verify` and no block differences against the journal path on a sample of chunks.

### 3. Per-chunk status graph and parallel features
- Replace "rank then one ordered lane" with a per-chunk status (terrain, structure, features, light, full) and neighbour requirements. Let feature jobs with non-overlapping windows run on different workers at once (a window lock, as 1.21 locks regions).
- Only worth it after step 1 or 2: the workers are mostly idle today (busy 5 to 35 percent), so throughput is limited by the main-thread commit, not worker capacity.

### 4. Remaining inline fallbacks and server-only generators
- Serene Seasons and Rustic call `getPerWorldStorage` during populate: a read-only snapshot of the few saved-data objects they read would remove about 1.6 percent of populates from the inline path. `getMapStorage` (Mystical World structures) and `spawnEntity` (Meson helper, Charm) are similar.
- The lock wait in the inline fallback path (`FeatureLane.applyJob`, `DECO.lock()`) was 1.5 to 1.8 percent of the server thread. Making it non-blocking means deferring a half-applied job; do it with a flight to test it.
- Server-only generators one at a time, only if a profile shows one dominating: Thaumcraft (aura), Astral Sorcery, The Betweenlands, Recurrent Complex. Each needs its own analysis of what world state it touches.

### 5. Move the spawn stage out of the populate tail
- RTG's tail runs `WorldEntitySpawner.performWorldGenSpawning` (animals) inside `RtgSplitAccess.chunksteward$tail`. Schedule it as its own small task after the commit so it cannot extend a tick. Small.

### 6. Light for gameplay (optional, large)
- 1.21's light engine also handles ongoing block changes on its own thread. Ours only computes light for generation and for `light fix`. An existing 1.12 lighting engine (Alfheim, Phosphor port) may cover this; check compatibility with Depths Update before building anything.

## Out of scope

Entity updates, the vanilla mob spawner loop (see the Tweaks spawning work), fluid flow and random ticks. 1.21 pays those on its main thread too.

## Switches and commands (for testing)

- Config: `features.enabled`, `features.modGenerators`, `features.lightStage`, `features.tileEntityBlocks`, `pipeline.depthsPass`, `pipeline.biomeSnapshot`, `pipeline.maxReady`, `pipeline.sweepReady`, `pipeline.readyHeadroom`, `populate.stagedDrainPerTick`, `stage2.*` (`maxOpsPerSecond`, `dwellSeconds`, `maxHotspotDistanceChunks`, `requirePlayerPreload`), `preload.slowTickBackoffTicks`, `spawner.pauseWhileFast`.
- Commands: `/chunksteward status`, `pipeline status|verify`, `light verify|fix|status`, `hotspots here`, `pregen`, `chunkinfo`.
- Telemetry: the `status:` lines in `latest.log` (`tick`, `stages`, `pipeline`, `staged populate`), and `logging.pipelineTrace` for `logs/chunksteward-trace.csv`.

## Constraints and lessons

- Commits: no `Claude-Session` trailer; keep `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. No pull requests unless asked. Check that the build and tests passed before pushing (`bash ./gradlew --offline build`).
- The user tests in their full pack and sends `latest.log`, Spark profiles (I parse `.sparkprofile` protobuf with a small script) and screenshots. A startup crash costs them a launch; three came from unflown mixin hooks.
- Mixin rules: a plain class or interface must not live in `com.apocollis.chunksteward.mixin` (put it in `server`); `@Accessor`/`@Shadow` names must be the MCP names, and the refmap must map them. `verifyReleaseJar` in `build.gradle` fails the build for both.
- Forge keeps saved config values when a default changes. Rename a key when its new default must take effect (done for the stage 2 settings).
- When the API changes, refresh `libs/ChunkSteward-1.1.jar` in the Tweaks repo (`/home/user/arcanaquesttweaks`) before building it, and rebuild Tweaks.
- Do not read `getLoadedChunk` for lookups that must not keep chunks loaded: it clears `unloadQueued`. Use `StageGate.peek`.
- The feature verify command was considered and dropped. Equivalence of staged and inline populate is checked only by flying, the discard and abort counters, and the trace.
