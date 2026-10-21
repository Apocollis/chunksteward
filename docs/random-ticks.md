# Random-tick guard

`WorldServer.updateBlocks` (`func_147456_g`) runs random block ticks for every loaded chunk a player watches. Some of those ticks reach a few blocks past their own chunk: lava (`BlockStaticLiquid.updateTick`, looking for flammable blocks to ignite), Biomes O' Plenty grass spread, and similar. At the edge of the watched area that read lands in a chunk that is not loaded, and in 1.12 `World.getChunk` generates it synchronously. In a 10-minute server-thread profile that was about 17 s of tick work, and it kept extending the generated area outward.

## What the guard does

`MixinWorldServer` sets a flag at `updateBlocks` HEAD and clears it at RETURN (inject only, so it cannot clash with other mods that wrap the `randomTick` call). `MixinChunkProviderServer` asks `RandomTickGuard.intercept` at `provideChunk` HEAD. While the flag is set and the chunk is not in `loadedChunks` (dimension not excluded), it returns an `StewardEmptyChunk (Chunk Steward's own empty chunk: vanilla `EmptyChunk` is client-only and does not exist on a dedicated server)` for that position: reads see air, writes are dropped, no chunk is generated. Everything outside the random-tick window, and every loaded chunk, is unchanged.

## Effect

Inside loaded terrain: none. At the edge: a tick that would have crossed into unloaded terrain does nothing; when that terrain loads, those blocks tick normally and the spread continues. Nothing is removed or cancelled. This matches newer Minecraft, where border chunks do not tick across their edge.

Possible differences: light read from an empty chunk is the default (sky 15, block 0) for that one read, so a mod that checks light across the border may misjudge it for a tick. A mod that expects a write to succeed there sees it ignored.

## Config and metrics

`randomTicks.guardEnabled` (default true). The status log shows `random-tick guard N (sampled: class count ...)`; sources are sampled (at most 8 per tick, only while status logging is on) from the first calling class that is not world or server plumbing, normally the block that was ticking.
