# Stage 2: home-area pregen

Areas where players spend time (a bed, or a place they stay for about `dwellMinutes` in total) become **hotspots**. While a player is near a hotspot and the server has room, stage 2 generates the area terrain-only out to `radiusTerrain` (30), then populates it out to `radiusFull` (20). It is on by default (`stage2.enabled`).

Note: a pregenerated area keeps the worldgen settings of the moment. Worldgen config changes (RTG, villages, ore) only affect chunks generated after them. Turn stage 2 off, or remove hotspots, on worlds where you test worldgen.

## Finding hotspots (`Hotspots`)

**Dwell sampling.** Every `dwellSampleSeconds` (10 s) each player (not a spectator, dimension not excluded) is compared with a fixed anchor position:
- within `dwellAnchorRadiusBlocks` (32, horizontal): idle. The sample (10 s) is credited to the player's cell and the anchor stays;
- farther: no credit, and the anchor moves to where the player is.

The anchor is deliberately fixed while the player stays near it. If it followed them, a slow mover (sneaking at 1.3 blocks/s covers 13 blocks per sample, a slow ride) would count as idle forever and build dwell along the whole path. A dimension change resets the anchor. Walking pace (4.3 blocks/s, 43 blocks per sample) always exceeds the radius.

**Cells.** The world is divided into cells of `cellSizeChunks` (8 chunks = 128 blocks). Each cell has an accumulated-seconds counter per dimension, shared by all players (one credit per cell per sample, however many are present). The counters live in `HotspotData` and are saved with the world; they are cumulative, so they resume after a player leaves and returns, or the server restarts. At most 4096 cells are kept; past that the lowest quarter is dropped.

**Qualifying.** When a cell is credited, the sum over the 3x3 cells around it is compared with `dwellMinutes * 60` s (10 min). A base that spans several cells still qualifies. At or above it, a hotspot is created at the dwell-weighted centroid of those cells, unless one already exists within `mergeRadiusChunks` (8).

**Beds.** A player's bed or respawn point in their dimension (`EntityPlayer.getBedLocation(dim)`) is a hotspot at once, checked at login and every minute, merged if close to an existing hotspot. `homeFromBed`.

There is no limit on the number of hotspots: a finished hotspot costs nothing more, and stage 2 only works near players. Instead `warnTotalChunks` (100,000, about 0.9 GB at 9.4 KB per chunk) logs a warning and tells operators, and `maxTotalChunks` (default 0 = none) is an optional hard limit on chunks stage 2 has generated (all dimensions).

## Doing the work (`Stage2Pregen`)

A hotspot is worked on only when all of these hold:
- a player is within `activeRadiusChunks` (8) of it and is not a spectator;
- that player's preload queue (stage 1) has been built and emptied (`IdlePreload.stage1Done`);
- the player is not fast (the moving-tier speed limit, `preload.movingMaxSpeed`);
- `Pregen.holdOffReason(stage2.maxTickMs)` is empty: no chunks waiting for players, no recent busy generation (`preload.busyChunks`), no populate backlog (`preload.maxPopulateBacklog`), no save due, tick average and current tick under `maxTickMs` (30);
- `/chunksteward pregen pause` is not active and `maxTotalChunks` is not reached.
- A pipeline verify is not running in the player's dimension ([pipeline.md](pipeline.md)).

At most one operation every `intervalTicks` (4) and `maxPerMinute` (240) per hotspot.

Work goes in square rings out from the hotspot's center chunk:
1. **Terrain phase**, rings 0..radiusTerrain: each chunk that is not loaded, not known (`GeneratedCache`) and not on disk is generated terrain-only (`StageGate.beginTerrainOnly`: populate deferred) and released at once. This makes the outer neighbours that every later populate needs exist on disk. When `pipeline.speculative` is on and the dimension is being served, that generate is queued behind player chunks. The ring keeps the chunk at its head until a finished result can be installed, and this interval does not advance the ring.
2. **Populate phase**, rings 0..radiusFull: the target is loaded; if it is not yet terrain-populated, its +x, +z and +x+z neighbours are loaded too (vanilla populates a chunk when those exist) and, if needed, `chunk.populate(provider, generator)` is called. `StageGate.beginStage2()` lets these populates past the budget deferral (stage 2 has already checked there is room); cascades inside them are still deferred as usual, and mostly hit chunks that already exist. The four chunks are held for 100 ticks (the next target shares neighbours) and released through `ChunkHolds`.

Stage 2's own terrain generation is not counted as "external generation" for the busy hold-off (`StageGate.onGenerate` ignores it); otherwise it would stop itself after six chunks. Scanning chunks that already exist still costs a disk check or load, so a tick stops scanning once it passes `maxTickMs`.

**Progress** is the last completed ring of each phase, kept in `HotspotData` per hotspot (`terrainRing`, `popRing`). A hotspot is done when `terrainRing >= max(radiusTerrain, radiusFull + 1)` and `popRing >= radiusFull`, evaluated against the current config, so raising a radius continues the work. A ring cut short by a restart is redone: chunks already generated or populated only cost a load.

## Saved data (`HotspotData`)

`WorldSavedData` named `chunksteward_hotspots` in the dimension's own data folder (`World.getPerWorldStorage()`): hotspots (id, center chunk, source, ring progress, counts, last worked), dwell counters, `nextId`, `pregenTotal`. A missing or unreadable file only restarts the counters and hotspot list; chunks are never touched.

## Config (`stage2`)

`enabled` (true), `radiusFull` (20), `radiusTerrain` (30), `dwellMinutes` (10), `dwellSampleSeconds` (10), `dwellAnchorRadiusBlocks` (32), `cellSizeChunks` (8), `mergeRadiusChunks` (8), `homeFromBed` (true), `activeRadiusChunks` (8), `intervalTicks` (4), `maxPerMinute` (240), `maxTickMs` (30), `warnTotalChunks` (100000), `maxTotalChunks` (0).

Disk: about 9.4 KB per chunk measured on a test world, so about 35 MB for a full 61x61 hotspot. Natural villages and Roguelike dungeons inside the area are generated while the hotspot is worked (their jobs run under their own budgets).
