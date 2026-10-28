# Preload (idle and moving tiers)

`IdlePreload` (server tick END) generates chunks beyond the view distance around players while the server has spare time. At most one chunk every `intervalTicks` (2), round-robin over players, at most `maxPerMinute` (90) per player per minute.

All radii are measured from the live view distance (`PlayerChunkMap.playerViewRadius`, read through `AccessorPlayerChunkMap`), and the square the vanilla loads for the player (`|dx| <= view and |dz| <= view`) is excluded: preload never repeats what vanilla provides. At view distance 8, `idleExtraFull = 2` is radius 10 and `idleExtraTerrain = 12` is radius 20.

## Tiers

| | Moving | Idle |
| --- | --- | --- |
| Condition | not idle, `speed <= movingMaxSpeed` (0.30 blocks/tick: walking and sprinting, not flying or elytra), `movingEnabled` | no movement over 0.25 block for `idleStillTicks` (20 = 1 s), checked every `movement.sampleTicks` (10) ticks |
| Full generation | none | out to view + `idleExtraFull` (2) |
| Terrain only | forward cone (`movingForwardAngleDegrees` half-angle, 60) out to view + `movingExtraTerrain` (3) | out to view + `idleExtraTerrain` (12; 0 = off) |
| Tick limit | tick EMA and current tick under `movingMaxTickMs` (15) | under `maxTickMs` (20) |
| Order | nearest first inside the cone | front cone, sides, behind (full before terrain-only), nearest first |

Direction comes from `PlayerMotion`: view direction when slow, travel direction when fast.

## When preload holds off (every tier)

- chunks are waiting for any player (`TickMonitor.waitingNow()`, entries without a chunk): preload yields to demand;
- `busyChunks` (6) chunks were generated in the last `busyWindowTicks` (100) by anything but preload (`TickMonitor.recentExternalGen`, counted at the terrain stage): the server is busy generating chunks players need; Chunks generated while held by a Forge ticket of a mod in `busyIgnoreMods` (default `roguelike`) are not counted: that is the mod's own build (a dungeon footprint, which Roguelike forces on its ticket right before loading each chunk), not player demand. Counted in the status log as `mod-ticket generation N`.
- more than `maxPopulateBacklog` (4) populates are queued ([populate.md](populate.md));
- the tick is over the tier's limit, a save is pending, or the dimension is excluded;
- a pipeline verify has paused the player's dimension ([pipeline.md](pipeline.md)).

The status log counts each yield (`yielded to demand/busy/populate backlog`).

## Queue

Built when the tier starts, when it changes, when the view distance changes, and for the moving tier when the player enters a new chunk or turns past about 30 degrees (at most every 10 ticks). Idle players keep one queue. Moving players have a queue only while they are slow.

Chunks already loaded or known to exist are skipped (at most 16 checks per tick). `GeneratedCache` keeps positive answers (a bounded set per dimension, filled by every generation and by disk checks), so most checks do not touch the region file; `isChunkGeneratedAt` ends in a `File.exists` per call. Loaded checks use `StageGate.peek`, not `getLoadedChunk`, which would cancel a pending unload.

## Full chunks

Generated with `ChunkProviderServer.provideChunk` on the server thread. When `pipeline.speculative` is on and that dimension's generator is being served, a chunk that is not already finished is put back at the head of the player's queue and this interval does not count. The next `provideChunk` runs only to install the worker result, still inside the terrain-only or full-preload scope. A chunk is populated only once its neighbours exist, so each full chunk is held loaded for `holdTicks` (200) through `ChunkHolds` and then released with `queueUnload` (which saves it). `maxHeldChunks` caps the hold list, shared with cascade chunks. Chunks a player watches or a Forge ticket owns are never released.

## Terrain-only chunks

Generated inside `StageGate.beginTerrainOnly`/`endTerrainOnly`; the populate gate defers their populate, so they are saved with `TerrainPopulated = false`. They are released at once. Vanilla populates them later when they are loaded next to their neighbours, or [populate.md](populate.md) does when a player claims them while loaded.

## Release re-check

Vanilla's neighbour populate check calls `getLoadedChunk`, which clears `unloadQueued`. `ChunkHolds` re-checks every released chunk every 20 ticks, up to 10 times, and issues `queueUnload` again if it is still loaded and unowned.

## Disk

Terrain-only chunks stay on disk. Measured on a test world: about 9.4 KB per chunk (357 MB for 38,772 chunks). The per-minute limit and the busy and demand holds keep the growth bounded. Test on a copy of a world.
