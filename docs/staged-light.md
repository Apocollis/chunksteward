# Staged light (design, in progress)

Goal: light becomes its own stage of a chunk build, run on a terrain worker, like 1.21's `light` status. Today it is the largest single cost on the server thread of a populate: in two 2026-10-28 profiles about 27% of the thread went to light, 11 to 13% in Arcana Quest Tweaks' deferred relight queue (`PopulateRelight.replay`) and 12 to 16% forced early inside a staged job's apply, because a mod's populate event (Serene Seasons' `SeasonHandler.onPopulateChunk`) reads the rain height of every column and each read replays that column's pending light work. Moving that event to a worker would not save anything; the cost is the light work, wherever the heights get read.

## What vanilla computes

`World.checkLightFor` settles on the largest fixed point of one rule per cell:

```
opacity' = opacity;  if (opacity' >= 15 and emission > 0) opacity' = 1;  if (opacity' < 1) opacity' = 1
value    = opacity' >= 15 ? 0 : max(source, max over the 6 neighbours of (light(neighbour) - opacity'))
source   = sky light: 15 at or above the column's height, else 0;   block light: the emission
```

A column's height is one above its highest cell with opacity above zero. Because that fixed point is unique, a worker can compute a chunk's light from scratch, in one flood, and get the values vanilla's incremental updates converge to.

## Pieces

1. **`light.LightEngine`** (done, pure Java, tested against a brute-force fixed point): a box of cells with opacity and emission, optional fixed cells (the already-lit ring around the region), sky above the box. One bucket flood for sky light and one for block light.
2. **Window adapter** (next): on a worker, after the job's stages, build a box from the 4x4 read window plus the job's journal (the write window's chunks are the ones whose blocks changed): opacity from `IBlockState.getLightOpacity(world, pos)`, emission from `getLightValue(world, pos)` (answered by the job's scope, so a mod's context-dependent block works), the ring of the read window as fixed cells with the light the loaded neighbours have now. Output: sky and block nibble arrays for the write window's chunks.
3. **Server install** (next): at apply, after the block replay, write the arrays into the chunks' `ExtendedBlockStorage`, set `lightPopulated`, mark dirty. No flood on the server thread, and no per-column relight, so Tweaks' deferred replay has nothing to do for a chunk that took this path. A neighbour outside the write window whose border cells changed is not touched on the server: its own populate computes its light from a ring that includes this chunk.
4. **`/chunksteward light verify <n>`** (next): compares the engine's output with what vanilla settles on for n chunks of a loaded world (relight a copy the vanilla way, compare nibbles) and reports the first differing cell. The stage stays off (`features.lightStage`, default false) until a clean verify.

## Known risks, to be settled by the verify

- Vanilla's updater has a range limit and known inconsistencies; the engine gives the fixed point, so a difference in those cells is expected and arguably a fix. The verify reports counts by kind.
- Block light from a modded block whose opacity or emission depends on world context beyond its own position (TileEntity state, neighbours) is read through the scope; an unsupported read aborts the job and the chunk takes the old path.
- Depths Update's extended storage (negative Y) changes the box's height range; the adapter takes the range from the chunk's storage array.
- Cost: a 64x64x320 box is about 1.3 million cells. Per chunk that is tens of milliseconds on a worker. The workers are 5 to 35% busy, so it fits; a later change can skip all-air and all-stone sections.
