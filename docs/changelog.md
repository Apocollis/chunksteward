# Changelog

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
