# Staged light (experimental, `features.lightStage`)

Goal: light becomes its own stage of a chunk build, run on a terrain worker, like 1.21's `light` status. Today it is the largest single cost on the server thread of a populate: in two 2026-10-28 profiles about 27% of the thread went to light, 11 to 13% in Arcana Quest Tweaks' deferred relight queue (`PopulateRelight.replay`) and 12 to 16% forced early inside a staged job's apply, because a mod's populate event (Serene Seasons' `SeasonHandler.onPopulateChunk`) reads the rain height of every column and each read replays that column's pending light work. Moving that event to a worker would not save anything; the cost is the light work, wherever the heights get read.

## What vanilla computes

`World.checkLightFor` settles on the largest fixed point of one rule per cell:

```
opacity' = opacity;  if (opacity' >= 15 and emission > 0) opacity' = 1;  if (opacity' < 1) opacity' = 1
value    = opacity' >= 15 ? 0 : max(source, max over the 6 neighbours of (light(neighbour) - opacity'))
source   = sky light: 15 at or above the column's height, else 0;   block light: the emission
```

A column's height is one above its highest cell with opacity above zero. Because that fixed point is unique, a worker can compute a chunk's light from scratch, in one flood, and get the values vanilla's incremental updates converge to.

## Pieces (all built; the stage is off by default until a clean verify)

1. **`light.LightEngine`** (done, pure Java, tested against a brute-force fixed point): a box of cells with opacity and emission, optional fixed cells (the already-lit ring around the region), sky above the box. One bucket flood for sky light and one for block light.
2. **Window adapter** (`LightAdapter`): on a worker, after the job's stages, build a box from the 4x4 read window plus the job's journal (the write window's chunks are the ones whose blocks changed): opacity from `IBlockState.getLightOpacity(world, pos)`, emission from `getLightValue(world, pos)` (answered by the job's scope, so a mod's context-dependent block works), the ring of the read window as fixed cells with the light the loaded neighbours have now. Output: sky and block nibble arrays for the write window's chunks.
3. **Server install** (`LightStage.install`): at apply, after the block replay, write the arrays into the chunks' `ExtendedBlockStorage`, set `lightPopulated`, mark dirty. No flood on the server thread, and no per-column relight, so Tweaks' deferred replay has nothing to do for a chunk that took this path. A neighbour outside the write window whose border cells changed is not touched on the server: its own populate computes its light from a ring that includes this chunk.
4. **`/chunksteward light verify [n]`** (`LightVerify`): compares the engine's output with what vanilla settles on for n chunks of a loaded world (relight a copy the vanilla way, compare nibbles) and reports the first differing cell. The stage stays off (`features.lightStage`, default false) until a clean verify.

## Known risks, to be settled by the verify

- Vanilla's updater has a range limit and known inconsistencies; the engine gives the fixed point, so a difference in those cells is expected and arguably a fix. The verify reports counts by kind.
- Block light from a modded block whose opacity or emission depends on world context beyond its own position (TileEntity state, neighbours) is read through the scope; an unsupported read aborts the job and the chunk takes the old path.
- Depths Update's extended storage (negative Y) changes the box's height range; the adapter takes the range from the chunk's storage array.
- Cost: a 64x64x320 box is about 1.3 million cells. Per chunk that is tens of milliseconds on a worker. The workers are 5 to 35% busy, so it fits; a later change can skip all-air and all-stone sections.

## How it is wired

- The job's stages are the decorations, the mod generators and, with `features.lightStage`, the light stage (`LightStage`). It runs last on the worker, against the job's own view of the blocks (the chunks plus the journal), so it sees the trees and structures the job placed.
- At apply, after the journal is replayed, `LightStage.install` writes the nibble arrays into each of the four chunks' sections, sets the column heights of the columns the job wrote to (the worker's height-map rule), invalidates the rain-height cache, marks the chunks light populated (the centre chunk always; a neighbour only if its populate has started), and calls `PopulateScope.lightInstalled` (API version 5) for each chunk. Arcana Quest Tweaks drops the chunk's deferred relight record there, so the queue has nothing left to do for it, and its opening full light check is never needed.
- Cells just outside the window next to a border cell whose light changed get an ordinary `World.checkLight` on the server thread (at most `features.lightBoundaryChecks` per chunk), so light spilling past the window settles.
- A section that does not exist yet is not created by the install (the sections of a chunk below Y 0 in a Depths world are found by their stored Y, not an index); an all-air section's light stays implicit, as vanilla keeps it until something is lit there.
- If the stage aborts or fails, the job's other stages are applied and the light is made the old way. Without Arcana Quest Tweaks the replay does vanilla's light inline and the install overwrites it with the same values, so the stage is correct but saves no time.
- Status line: `light stage installed N, fell back M, boundary checks K`, and `worker time by generator` lists `light stage`.

## Testing it

1. `/chunksteward light verify 10` near populated, lit chunks. It prints the number of nibbles compared and how many differ (sky and block, and how many the worker has darker), with the first differing cells and their blocks. A handful of differences at chunk edges or around lava and glowstone is normal for vanilla's updater; thousands are a bug.
2. Turn `features.lightStage` on and compare the `pipeline:` and `staged populate:` lines: the apply time on the server thread should fall and the Tweaks `PopulateRelight.replay` share of the thread should shrink.

## Audit and fix (`/chunksteward light fix [radius]`)

The same engine can audit chunks that are already loaded. `light verify` showed block light missing near real emitters (fire, torch fungus, lava) in a world generated without the stage, so the audit finds those cells and fixes them.

- The server thread picks stride-2 2x2 windows around you and hands each to a terrain worker when it is idle (audits run behind generation work). The worker reads the window's blocks, computes sky and block light with the engine and returns the arrays.
- At tick end (3 ms budget) the server installs a result only if none of the window's chunks was replaced, held by a staged job, or had a block change since the audit began (`ChunkChanges` counts direct block writes per chunk). Otherwise the result is dropped and counted as "changed".
- An install uses the same code as the light stage, queues boundary `checkLight`s, and resends the changed sections: the client only receives light inside chunk data, so 64 distinct block changes in a section make the player map send it again.
- `light status` prints queued, running, audited, fixed, clean, changed and failed counts. Sections below Y 0 are installed but not resent.
