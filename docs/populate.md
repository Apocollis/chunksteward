# Populate budgeting and cascades

Populate (`Chunk.populate(IChunkGenerator)`, `func_186034_a`) is where trees, ores, structures and every mod's `IWorldGenerator` run. It is the largest part of a chunk's cost and it can arrive in bursts: chunks loaded from disk (including terrain-only preload chunks) populate as soon as their neighbours are loaded, through the load-only path that the throttle does not gate. Measured in 0.2 test logs, stalls of 12 s and 24 s were populate bursts with almost no terrain generation.

`StageGate.populateNow` decides, for each populate request, whether it runs now or is deferred to `PopulateQueue`.

## Rules (in order)

1. Terrain-only preload chunk while preload is generating: deferred (see [idle-preload.md](idle-preload.md)).
2. `populate.enabled` false, client world, another thread, excluded dimension: run.
3. The drain's own populate (depth 0): run.
4. A chunk held by a Forge chunk ticket owned by a mod in `populate.ticketExemptMods` (default `roguelike`), when not a cascade: take it out of the queue if queued, count it (`ticket-exempt`), run. Mods like Roguelike Dungeons load their footprint chunks across ticks (forcing each on their own ticket) and then call `chunk.populate(provider, generator)` themselves before building; a deferred populate would run after the build and could overwrite it. Chunks with no exempt ticket are unaffected. Cascades are never exempt.
5. Already queued: stay deferred.
6. Near a player (within `protectRadius` (2) chunks or that player's speed-scaled emergency radius), or within the view distance of a player who joined within `warmupTicks`: run.
7. A cascade (`populateDepth > 0` and `cascadeDeferral`): deferred. Nothing is cancelled: the chunk was generated terrain-only inside another populate, so the read or write that caused it saw real terrain; its own populate happens later.
8. Otherwise run while the current tick has used less than `tickBudgetMs` (30), or if no populate ran this tick and one is overdue by `maxStarveTicks` (4). Else deferred.
9. If the queue holds `maxQueued` chunks, run.

## Depth tracking

`MixinChunk` calls `StageGate.populateEnter(chunk)` after the gate allows a populate and `populateExit(chunk)` at RETURN. Frames record the chunk, so a RETURN after a cancelled head is ignored. Depth is reset at tick start (an exception inside a generator skips the exit).

## Cascades

`StageGate.onGenerate` (called from `MixinChunkProviderServer` before `IChunkGenerator.generateChunk`) sees every terrain generation. If a populate is running, that generation is a cascade: the chunk is counted (by the innermost running `IWorldGenerator` class in the status log), and, if `cascadeDeferral` is on, it is held for `cascadeHoldTicks` (200) and then released through `ChunkHolds` if no player owns it. Vanilla never unloads such chunks otherwise.

## Queue and drain

`PopulateQueue` keeps a position set and list per dimension (never a `Chunk`). At tick END it picks the lowest-scoring queued chunk (squared distance to the closest player in that world, scaled by `ordering.directionPenalty` as in [ordering.md](ordering.md)) and runs vanilla's `chunk.populate(provider, provider.chunkGenerator)`, the call a load would have made. Per tick: up to `drainPerTick` (3) while the tick is under `tickBudgetMs`; one anyway if the starvation guard says so; chunks near a player always run (at most 4 extra per tick). A chunk unloaded in the meantime is dropped: it is saved unpopulated and vanilla populates it after its next load. World unload clears that dimension.

Effect on players: a queued chunk is not `isPopulated`, so `PlayerChunkMapEntry.sendToPlayers` waits; it arrives when its populate runs. Chunks near the player are never held back.

## Limits

One populate cannot be split: a single heavy structure (village, dungeon) still costs its whole tick. The budget only keeps several from landing in the same tick.
