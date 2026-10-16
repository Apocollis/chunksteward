# Changelog

## 0.3
- Renamed from Lazy Chunks 1.12 to **Chunk Steward**: modid `lazychunks` → `chunksteward`, package `com.apocollis.chunksteward`, config `config/chunksteward.cfg` (same keys; copy `lazychunks.cfg` over), jar `ChunkSteward-<version>.jar`, mixin config `mixins.chunksteward.early.json`. New logo (credits in `NOTICE.md` and `mcmod.info`). The deploy script now also deletes `LazyChunks-*.jar`, since the old and new jars must never both load. Existing worlds log a one-time "saved with mod lazychunks which appears to be missing" notice, which is harmless.
- Populate budgeting: a populate that would push the current tick past `populate.tickBudgetMs` (30) is queued and run later, nearest and most-forward first; chunks near players, and players in login warmup, populate at once. Fixes the multi-second stalls of 0.2 test logs, which were populate bursts of chunks loaded from disk (including terrain-only preload chunks), not terrain generation. See `docs/populate.md`.
- Cascades in terrain-only mode, nothing cancelled: a chunk generated while another is populating is populated later through the queue, so ore, trees and village pieces still see real terrain and place normally. Cascade chunks no player owns are held and then released (vanilla never unloads them). Cascades are counted by the causing `IWorldGenerator` class in the status log.
- Random-tick guard: during `WorldServer.updateBlocks` an unloaded chunk read returns an empty chunk instead of generating (lava and fire spread, grass spread). `randomTicks.guardEnabled`. See `docs/random-ticks.md`.
- Preload rewritten: always-on, two tiers measured from the live view distance and excluding the area vanilla loads. Idle (still 1 s): full out to view + 2, terrain only out to view + 12. Moving (slow only): terrain-only forward cone out to view + 3. Holds off while chunks are waiting, recent generation is busy (`busyChunks`), populates are backed up, or ticks are over the tier limit; per-player per-minute limit; known-generated cache avoids repeated region-file checks.
- Config: new `populate`, `randomTicks` sections and new `preload` keys. Removed `preload.radius`, `terrainOnlyRadius`, `terrainOnlyMaxChunks`, `stationaryTicks` (the old keys in an existing cfg are dropped on the next write); `preload.maxTickMs` is now the idle tier's limit.
- Ticket exemption: a chunk held by a Forge chunk ticket owned by a mod in `populate.ticketExemptMods` (default `roguelike`) populates at once and is taken out of the queue, so Roguelike's dungeon build job is never racing a later queue populate. Counted as `ticket-exempt` in the status log. Cascades are never exempt.
- Held chunks (preload and cascades) share `ChunkHolds` and are never released while a player watches them or a Forge ticket owns them.
- Status log: populate queue, cascades by generator, held chunks, preload tiers and yields, random-tick guard counts and sources.
- Stage 2, home-area pregen (`stage2.enabled`, default on). Hotspots are a player's bed or respawn point, or any place where players have stayed about `dwellMinutes` (10) in total. Dwell is sampled every 10 s against a fixed anchor (32 blocks): within it the sample is credited to the player's 8x8-chunk cell, beyond it the anchor moves; a 3x3 block of cells reaching the threshold creates a hotspot at its dwell-weighted centroid. Counters are saved with the world and resume after a player leaves and returns. See `docs/stage2.md`.
- While a player is near a hotspot, after their preload queue has finished and while the server has room (the same hold-offs as preload), the area is generated terrain-only out to `radiusTerrain` (30), then populated out to `radiusFull` (20), one operation every 4 ticks. Progress is saved per hotspot and resumes after a restart.
- No cap on hotspots: `warnTotalChunks` (100,000) warns, `maxTotalChunks` (0 = none) can limit total chunks generated. Hotspots within `mergeRadiusChunks` (8) merge.
- Saved data: `chunksteward_hotspots` per dimension (`WorldSavedData`).
- Operator commands `/chunksteward` (status, chunkinfo, queue, holds, log, pregen, hotspots list/here/add/remove/reset). See `docs/commands.md`.
- Stage 2 populates pass the populate budget deferral through `StageGate.beginStage2()`; stage 2's own generation is not counted as busy generation.
- Status log: `stage2 hotspots N, generated, populated, yielded (last reason), total`.
- Populate and structure attribution in the status log: the populate stage is split into generator populate, structures (by `MapGenStructure` class), world generators and other; the terrain stage lists `MapGenBase` map-gens by class (caves, ravines, structure layout). Plain HEAD/RETURN injects on vanilla classes. See `docs/metrics.md`.
- Pacing API for other mods: `com.apocollis.chunksteward.api.ChunkStewardAPI` (average tick ms, headroom, remaining budget in the current tick, server-behind, generating-chunks, populate queue size). Read-only, additive. See `docs/api.md`.

## 0.2
- Direction-weighted order for missing chunks: distance penalised by angle from the player's view direction (still) or travel direction (fast); cached scores, stable sort; `directionPenalty = 0` is vanilla order. Re-sort when a player turns past `resortAngleDegrees`.
- Speed-scaled emergency radius above `emergencyMinSpeed`, capped at `emergencyMaxRadius`.
- Terrain-only ring for idle preload, from `radius` out to `terrainOnlyRadius` (default 20, cap `terrainOnlyMaxChunks` per spell), with populate suppressed through the new stage gate.
- `StageGate`: one gate for populate suppression and light deferral.
- Light deferral of the per-tick `checkLight` retry on terrain-populated, unlit chunks during heavy generation (default off), drained within a per-tick budget. Gated at `Chunk.checkLight()` HEAD, not in `onTick`, because Somnia's coremod (and Tweaks' overwrite of it) own that call.
- Status log: waiting chunks, wait time, stage timing (terrain, populate, light), top world generators by class, gate counters.
- Fix: preload peeked chunks with `getLoadedChunk`, which cancels a pending unload; it now reads `loadedChunks`. Released chunks are re-checked and re-queued if something pulled them back.

## 0.1
- Adaptive throttle, emergency bypass, login warmup, save pause, dimension list, status logging, idle preload.
