# Idle preload

`IdlePreload` (server tick END). A player is stationary after `stationaryTicks` within 0.25 block. When the server is idle (tick EMA and the current tick both under `maxTickMs`, no save pause) it generates at most one chunk every `intervalTicks` for one stationary player (round robin).

## Queue per stationary spell

1. Full chunks within `radius` (10), by class (under player, front cone within 60 degrees, sides, behind), then distance.
2. Terrain-only ring from `radius` out to `terrainOnlyRadius` (20; 0 = off), same classes (front, sides, behind), then distance. At most `terrainOnlyMaxChunks` (600) terrain-only chunks per spell; a full ring is about 940 chunks (circle).

Chunks already loaded or already generated on disk (`isChunkGeneratedAt`) are skipped (at most 16 checks per tick). Loaded checks use `StageGate.peek`, not `getLoadedChunk`, which would cancel a pending unload of that chunk (a leak in 0.1).

## Full chunks

Generated with `ChunkProviderServer.provideChunk` on the server thread. A chunk is populated only once its neighbours exist, so each one is held loaded for `holdTicks` and then released with `queueUnload` (which saves it). `maxHeldChunks` caps the hold list. Chunks a player already watches are never queued for unload. While preload generates a full chunk, populate is still kept away from terrain-only chunks (see below).

## Terrain-only chunks

Generated inside `StageGate.beginTerrainOnly`/`endTerrainOnly`; the `Chunk.populate(IChunkGenerator)` mixin cancels populate for them, so they are saved with `TerrainPopulated = false` (and no light). Vanilla populates them later in the normal way, when they are loaded next to their neighbours. Details and the "player arrives while it is still loaded" case: [stage-gate.md](stage-gate.md).

They are released (`queueUnload`) immediately instead of held. Holding them would let the next ring chunk's neighbour check populate them, which is the work the ring exists to skip.

## Release re-check

Vanilla's neighbour populate check (and anything else) calls `getLoadedChunk`, which clears `unloadQueued` and so cancels the release. Every released chunk is re-checked every 20 ticks, up to 10 times: if it is still loaded, not watched by a player and no longer queued, `queueUnload` is issued again.

Moving, changing dimension, or the server getting busy stops preload; the queue (and the terrain-only count) is rebuilt next time the player stands still.

## Disk

The ring adds up to `terrainOnlyMaxChunks` chunks per fresh spot. Test on a copy of a world.
