# Diagnostics

`logging.debug` (default false; leave it off in normal play, it is verbose) turns on verbose diagnostics for chasing chunk loading problems. Every line starts with `dbg`: `grep dbg latest.log`. It needs no status log setting. Two sanity checks run even with debug off, because they would mean a bug (at most one warning per 10 s): the random-tick guard still active while player chunks are provided (it would hand out empty chunks), and a player chunk map entry holding an `StewardEmptyChunk` (it would never load).

## Once a second

`dbg tick N: provide {...} | populate {...} | TPS, load, other EMA, chunk EMA, populate queue, held, waiting, guard active`

- **provide:** why each `providePlayerChunk` call went the way it did, with counts: `pass: load only` (the vanilla `canGenerate` flag was false, for example a spectator), `pass: throttle off`, `bypass: emergency radius`, `bypass: login warmup`, `bypass: dimension excluded`, `admitted`, `deferred: headroom`, `deferred: save`. Calls with `canGenerate` false are tagged `[canGenerate=false]`.
- **populate:** what the populate gate decided, `run` or `defer`, with the rule: `near a player or login warmup`, `within budget`, `over budget`, `cascade`, `already queued`, `pass: ticket exempt`, `pass: queue drain or stage 2`, `preload terrain-only chunk`, `queue full`.
- **TPS, load, other EMA, chunk EMA:** measured TPS and load factor (reported only), and the tick split into non-chunk work and chunk provision.

## Every tick, own surroundings

`dbg ALERT <player> at chunk (x, z) speed s emergency radius r: chunk (x, z) d=n is MISSING|UNPOPULATED: <detail> | last provide outcomes {...}`: the player's own chunk or one of the eight around it is not loaded or not populated (at most 4 per second).

## Around each player, once a second

`dbg near <player> chunk (x, z) speed s emergency radius r, view v: within R chunks populated P, loaded-unpopulated U, missing M (no chunk-map entry: E); missing within view by side: ahead A, left L, right R, behind B (direction, pos); first missing ring F; gaps by ring: r3 2m/1u ...`. The scan reaches the view distance + 1 (at least 4, at most 12 chunks). `missing within view by side` counts missing chunks relative to the player's direction; `first missing ring` is the nearest ring with a missing chunk; `gaps by ring` lists missing (`m`) and loaded-unpopulated (`u`) counts per ring. It is followed by up to eight of the nearest problem chunks with detail:
- `missing (x, z) d=n entry=NONE|yes, chunk null|set`: not loaded; whether the player chunk map has an entry that is waiting for it (entry NONE would mean nothing is asking for the chunk).
- `loaded-unpopulated (x, z) d=n queued= terrainOnly= held= watched= neighbours(+x,+z,+x+z)=y,n,y`: loaded but not populated; whether it sits in the populate queue, is a terrain-only preload chunk, is held, is watched by a player, and which of the three neighbours populate needs are loaded (an `n` means populate cannot run yet).

## Immediately, rate limited

`dbg NEAR chunk (x, z) d chunks from a player was deferred: headroom` and `dbg NEAR populate of chunk ... was deferred (rule)`: anything the throttle or the populate gate defers within 3 chunks of a player (at most 6 lines per second). Near chunks should never wait, so any such line is worth a look.

## Reading it

- Chunks missing near the player, entry NONE: the chunk map is not asking for them.
- Missing, entry yes, and the provide counts show `pass: load only` with `[canGenerate=false]`: vanilla is not allowed to generate them (a spectator, or the gamerule).
- Missing, entry yes, and `deferred: headroom` or `deferred: save`: the throttle is holding them.
- Loaded-unpopulated with `neighbours` containing `n`: populate is waiting for neighbours that are not loaded yet.
- Loaded-unpopulated with `queued=true`: they are in the populate queue (the drain runs a few per tick, within the budget).
- A `WARNING` line: the guard or an empty chunk, see above.
