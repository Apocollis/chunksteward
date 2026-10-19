# Prompt for the Roguelike Dungeons chat: faster dungeon footprint preparation

Paste everything below into the Roguelike chat (repo `C:\dev\minecraft-roguelike`). Observations come from the Chunk Steward 0.3 DEVBOX test of 2026-10-17 (`latest.log`, 16:39:51 to 16:41:04). They are log readings, not code reading: verify against the Roguelike code before acting.

---

## Goal
Shorten the time between a dungeon being queued and it being playable, without causing server stalls, and avoid leaving the player standing in unpopulated chunks inside the footprint while the dungeon is being prepared. Follow the repo workflow: investigate read-only, write a short plan, then STOP and wait for an explicit "proceed" before editing or building. Do not push or pull Git.

## Context
- The server mod "Chunk Steward" (modid `chunksteward`, API `com.apocollis.chunksteward.api.ChunkStewardAPI`, version 2) is installed. It throttles chunk generation for players and reports pacing via the API: `getAverageTickMs`, `getNonChunkTickMs`, `getHeadroomMs`, `getMeasuredTps`, `getLoadFactor`, `getRemainingTickBudgetMs(targetMs)`, `isServerBehind`, `isGeneratingChunks`, `getPopulateQueueSize`. Meanings must not change. Call it only inside a holder class guarded by `Loader.isModLoaded("chunksteward")`.
- Chunk Steward treats chunks that Roguelike holds a ticket for as populate-exempt (`populate.ticketExemptMods = roguelike`), so it does not defer your populates. Player chunk generation and your footprint preparation compete for the same tick time.
- Measured TPS was 20.00 for most of the test; the load factor dropped to 0.3 to 0.5 for about 15 s while the player's own view chunks generated (this is Chunk Steward's own generation, not Roguelike's).

## The test (what happened)
The tester ran a locate (`Located RoguelikeDungeon at [-6712, ~, -3688] (408 blocks away)` at 16:39:51), which queued the dungeon, then teleported to it at about 16:39:55. The terrain around the dungeon was never generated, so this is a worst case (a locate-forced dungeon plus teleport arrival), not a natural approach.

| Time | Event |
|---|---|
| 16:39:51 | Dungeon queued (`LAYOUT` 42 ms work, 108 ms elapsed). |
| 16:39:52 | `Preparing 208 footprint chunks` (inner 143, populate set 175, load-only 33). |
| 16:39:52 to 16:40:16 | LOAD pass. Individual `Slow prepare step` lines of 20 to 33 ms, one chunk each, state before `UNGENERATED`. About 24 s for roughly 175 chunks, which is about 0.35 chunk per tick. |
| about 16:39:55 | Player arrives. 199 to 204 chunks are waiting for the player's own view. |
| 16:40:06 | `Can't keep up!` 2165 ms behind, 43 ticks skipped. Non-chunk tick time was 74 ms in that window. |
| 16:40:16 to 16:40:25 | POPULATE pass (173 ticks, about 9 s). Steps of 20 to 48 ms, one 123 ms. `Preparing footprint: pass POPULATE, 0 to load, 6 to populate, 0 others, 148 heavy steps (17 forced).` |
| 16:40:25 | `PREPARE_CHUNKS` done: `work 3284ms (longest single step 123ms)`, `elapsed 33314ms`, `ticks at full TPS 144`, `ticks the shared budget was trimmed 47`, `ticks heavy work was held back 436`, `78 populates suppressed`, `154 heavy steps (20 forced)`. |
| 16:40:39 | `ENCASE`: work 957 ms, flush 1033 ms, **elapsed 13716 ms**, ticks at full TPS 89, held back 91, light queued 264966, drained 241314, pending 23652. |
| 16:40:46 | `TUNNELS`: work 239 ms, **elapsed 7452 ms**, pending light 17012. |
| 16:40:57 | `ROOMS`: work 1468 ms (longest step 112 ms), **elapsed 10764 ms**, pending light 2938. |
| 16:40:58 to 16:41:01 | `SEGMENTS`, `LINKS`, `TOWER`, `FILTERS`, `LOOT`, `AFTER`: together about 4 s elapsed, work under 0.4 s. |
| 16:41:01 | Dungeon generated. Total about 70 s from queue to done; total real work is roughly 6 s. |
| 16:41:04 | `Light drained ... in 55 ticks, flush 537ms, ... drained 25247`. |

Other observations:
- While the player stood in the footprint (from about 16:39:55), their own chunk and the 8 around it were loaded but **unpopulated for about 11 s**, until the POPULATE pass reached them at 16:40:16 to 16:40:25. Chunk Steward was not holding them (`queued=false held=false terrainOnly=false`, +x/+z/+x+z neighbours loaded). The cause is Roguelike's staging.
- Roguelike logged `78 populates suppressed` in the prepare stage.
- During the whole prepare, the server hovered between 17 and 20 TPS and recovered to 20 TPS after 16:40:12, but the LOAD pass still advanced about one chunk per 2 to 3 ticks.

## Questions to answer from the code first
1. What limits the LOAD pass to about 0.35 chunk per tick: a fixed per-tick slice, the shared budget, a `getLoadFactor`/`isServerBehind` multiplier, or the "heavy work held back" gate? Which one produced "held back 436 ticks" and "budget trimmed 47 ticks"?
2. Why does elapsed exceed work by 10x to 30x in `ENCASE`, `TUNNELS` and `ROOMS`? Is it the light queue drain (pending 17k to 24k) or the slice size?
3. Why does the POPULATE pass wait for the whole LOAD pass? Does it need to?
4. Where are the 78 populates suppressed, and why are the player's chunks among them?
5. Do you scale by `getLoadFactor` at all today, and if so, would that double-throttle with Chunk Steward's own backoff when the load factor drops because of player chunk generation?

## Suggested changes (verify each against the code before adopting)
1. **Budget from measured headroom.** When `getMeasuredTps() >= 19.5`, run as many prepare steps per tick as `getRemainingTickBudgetMs(targetMs)` allows, not one. Apply load-factor or `isServerBehind` scaling only when measured TPS is actually below 20. Keep a hard cap per tick so one tick cannot exceed about 50 ms with a 123 ms step; split or defer steps that cannot fit.
2. **Nearest-first ordering.** Order load and populate by distance from the nearest player, so the chunks around a player inside the footprint are prepared first.
3. **Overlap LOAD and POPULATE.** Populate a chunk as soon as its +x, +z and +x+z neighbours are loaded, instead of waiting for the whole footprint to load. Chunk populate needs those three neighbours in 1.12.2.
4. **Do not suppress populates for chunks a player is in or beside.** At minimum, populate the player's chunk and its 8 neighbours first, so the player is never standing in an unpopulated pocket for the whole prepare.
5. **More light drain when there is room.** The later stages spend most of their elapsed time waiting on the light queue. When the average tick time is under about 40 ms (`getAverageTickMs`), drain a larger share of the light queue per tick.
6. **Keep the existing safety.** Leave the 123 ms single-step maximum behaviour, the slow-step logging and the stage summary format as they are, so the next test can compare against the numbers above.

## Success criteria for the next test (same locate-and-teleport worst case)
- `PREPARE_CHUNKS` elapsed well under 33 s (target under 15 s) with `ticks at full TPS` not lower than now.
- The player's own chunk and the 8 around it are populated within about 3 s of arrival (no long run of Chunk Steward `dbg ALERT ... UNPOPULATED` lines).
- Measured TPS does not fall below the current 16.5 to 17 low point, and no new `Can't keep up!` lines beyond the one stall seen at 16:40:06.
- `ENCASE`, `TUNNELS` and `ROOMS` elapsed times drop toward their work times; total queue-to-done time well under 70 s.
- Also test a natural walk-up (no locate, no teleport) to confirm the change helps outside the worst case.

## Second test (2026-10-18, two dungeons, same setup, plus a walk-up)
Dungeon 1 queued 15:13:33 at (952, 50, -2920), finished 15:16:01 (148 s). The tower stage ran at 15:15:57 to 15:15:58, about 145 s after queueing.

| Stage | Work | Elapsed | Held back (ticks) | At full TPS (ticks) |
|---|---|---|---|---|
| PREPARE_CHUNKS (251 chunks) | 1.23 s | 55.5 s | 740 | 12 |
| ENCASE | 1.07 s (+1.26 s flush) | 26.8 s | 165 | 94 |
| TUNNELS | 0.24 s | 37.3 s | 500 | 17 |
| ROOMS | 1.32 s | 16.6 s | 0 | 49 |
| SEGMENTS to AFTER | under 0.7 s | about 7 s | 0 | about 80 |

Total real work is about 7 s inside 148 s, which is under 5% use. Dungeon 2 (queued 15:18:06, 201 chunks): PREPARE_CHUNKS 49.3 s for 2.3 s of work (held back 789 ticks, trimmed 108), ENCASE 31.0 s for 0.95 s of work (held back 400). Ignore its TUNNELS 93 s: the integrated server was paused at the game menu from 15:19:28 to 15:20:59.

Server state while the dungeon crawled: measured TPS 20, average tick 15 to 47 ms (for example 27 to 44 ms from 15:14:27 to 15:15:07, 15 to 47 ms from 15:15:37 to 15:16:30), so 5 to 35 ms per tick was free and unused. The slow stages are exactly the ones with large "held back" counts (TUNNELS: 500 of about 745 ticks).

The scheduler code (`DungeonGenerationScheduler.applySteward`) shows two things that match this:
- At `loadFactor >= 1.0` it still sets `paused = generating` (Chunk Steward's `isGeneratingChunks()`, true when 6 or more player-needed chunks were generated in the last 100 ticks). That pauses the dungeon during every player chunk burst even at 20 TPS.
- `stewardPace.restrictHeavy(...)` and the `STEWARD_MAX_INTERVAL_TICKS` interval still hold heavy steps back in the "held back" counts above even with spare time. I could not find the `stewardPace` class source in the `1.12` tree, so check where the logic lives.

### Required behaviour (from the user)
Use as much tick time as Chunk Steward reports available. Throttle only when measured TPS stays below 20 for a sustained period (the load factor reflects the last 100 ticks), not on a single slow tick and not merely because chunks are generating. At 20 TPS with 35 ms average tick, the dungeon should take roughly the 15 ms headroom every tick.

### Tower must not appear on a player
The tower (stage 8 `TOWER`) is built about 2 minutes after the dungeon is queued, while the player may already be standing in the footprint. The dungeon is queued when a nearby chunk populates, which is when the player is within about view distance, so there is no lead time. Evaluate, in this order:
1. Make the whole dungeon fast enough that it finishes before a walking player (about 4.3 blocks per second) crosses the footprint. At full headroom use, the earlier numbers suggest roughly 20 to 30 s, not 148 s.
2. Delay the visible stages (at least `TOWER`, plus anything above ground) until no player is within a chosen distance of the footprint (or the player is inside it), and keep those chunks from being resent mid-build if possible.
3. Queue the dungeon earlier. Chunk Steward's preload does not populate beyond view distance, so a dungeon queued by population cannot start earlier unless Roguelike also triggers on a larger radius (for example a terrain-ready check). Say so if you need Chunk Steward to populate farther ahead; that is a Chunk Steward change the user must approve.

## Policy change requested (2026-10-18, after the spark profile)
A spark profile of the 21:22 to 21:29 run (server thread, 403 s) shows the dungeon is still timid even though the user accepts hitching:
- Dungeon 2 (queued 21:24:38, done 21:26:24, 106 s): PREPARE_CHUNKS 47 s for 3.4 s of work, ENCASE 25 s for 1.0 s of work plus 1.0 s flush at an average slice of 1 ms, ROOMS 10.8 s, AFTER 7.8 s (held back 101 ticks).
- During the build (about 21:25:30 to 21:26:20) the server thread was 90.6% busy and asleep 9.3% (about 4.6 ms per tick spare). Roguelike took 11.4% (about 5.7 ms per tick). Entity updates were 38%, Dynamic Stealth 22%, Depths Update 13%, chunk generation 3.5%.
- Dungeon 1 ENCASE (21:23:09 to 21:23:28): 19 s for about 1.7 s of work plus flush, with 15% of the thread asleep.
Because the spare time is small, a headroom-sized slice cannot make the dungeon fast. The user wants the dungeon to **overspend**.

Required behaviour:
1. While a dungeon job is running, spend a fixed per-tick cap (a config value, start around 40 to 45 ms of dungeon work per tick, so the tick may reach roughly 90 to 100 ms) regardless of `getHeadroomMs()`, `getRemainingTickBudgetMs`, `getLoadFactor()` and `isGeneratingChunks()`. Run steps until the cap is reached; steps are atomic, so the cap is soft and a long step (up to about 270 ms seen) may overrun it.
2. Intermittent hitching is accepted. Do not pause on a single slow tick, on TPS between 20 and about 12, or because Chunk Steward is generating chunks.
3. Pause (fall back to the headroom-based slice) only on a **sustained freeze**: for example `getMeasuredTps()` (100-tick mean) below about 10 for 10 consecutive seconds, or the dungeon's own measured tick time above a hard ceiling for several ticks in a row. Resume overspending after TPS is back above about 15 for about 5 seconds (hysteresis, so it does not flap).
4. Keep a hard safety: never let one tick run longer than a config ceiling (start at 200 ms) from dungeon work alone. Clients drop after about 30 s without a keep-alive and a dedicated server's watchdog (`max-tick-time`, default 60 s) can kill the server, so the overspend must never hold the thread for seconds at a time.
5. Make the cap, the sustained-freeze TPS and its duration, the resume TPS and the per-tick ceiling config values, and log the active mode (overspend or backed off) in the stage summaries so the next test can be read.
6. Priorities between stages: PREPARE_CHUNKS and ENCASE hold most of the waiting; ENCASE reports an average slice of 1 ms, so check that the overspend also reaches the light drain and not only the build steps.

Expected effect on Chunk Steward (so the next test is read correctly): while a dungeon overspends, measured TPS falls below 19.5, so Chunk Steward's full-TPS admission stops applying and player chunks go back to the headroom rule (`targetTickMs` 40), with the emergency radius still bypassing it. Slower player chunk loading during a dungeon build is therefore expected and accepted. No API change is needed for this: `getMeasuredTps()` and `getLoadFactor()` already give the smoothed signal.

## Constraints
- Do not change Chunk Steward's API meanings; the Roguelike jar compiles against it only.
- Keep the guard `Loader.isModLoaded("chunksteward")` and the holder-class pattern.
- Report findings in text; do not edit the Chunk Steward repo (`C:\dev\lazychunks`) from this chat.
