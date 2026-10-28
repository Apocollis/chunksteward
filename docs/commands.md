# Operator commands

`/chunksteward <subcommand>`, permission level 2 (operators; in singleplayer cheats must be on). Everything is read-only except the session switches (`log`, `pregen`, `pipeline off|on`) and the hotspot edits (`hotspots add|remove|reset`). Nothing here generates chunks.

## Information

| Command | Shows |
| --- | --- |
| `status` | version, which features are on, tick average, populate queue size, held chunks, chunks waiting for players, stage 2 hotspots and total pregenerated against the warning and limit, what stage 2 is doing now and its last hold-off reason, and the pipeline line (idle unless a generator class is listed) |
| `chunkinfo [x z]` | for the chunk you stand in, or the given chunk coordinates: loaded, terrain-populated, light-populated, on disk, queued for populate, terrain-only preload chunk, held by Chunk Wizard (ticks left), watched by a player, the centre biome, which path generated the terrain this session (terrain worker or server thread), Forge tickets and their owner mods. The quick answer to "does this chunk have a hole?" |
| `light verify [chunks]` | computes the light of loaded, populated, lit chunks near you with the worker's light engine and compares it with the light they hold; prints how many nibbles differ and the first few cells. Runs on the server thread for a moment. See [staged-light.md](staged-light.md) |
| `light fix [radius]` | audits the light of loaded, populated chunks within `radius` chunks of you (default 6, max 16) on the terrain workers and installs the corrections the light engine finds; chunks that changed meanwhile are dropped. Affected sections are resent to watching players. Works without `features.lightStage`. See [staged-light.md](staged-light.md) |
| `light status` | queued and running audit jobs, and the totals of chunks audited, fixed, clean, changed meanwhile and failed |
| `queue` | populate queue size and the first 10 entries |
| `holds` | held chunks (preload, cascades, stage 2) and when the oldest hold ends |

## Troubleshooting

| Command | Does |
| --- | --- |
| `log <seconds>` | sets the status log interval for this session (0 = off); not saved to the config |
| `pregen pause` / `pregen resume` | pauses or resumes preload and stage 2 for this session, to rule them out |
| `pipeline status` / `pipeline off` / `pipeline on` | pipeline state. `off` stops it for this session; `on` clears that. With no generator class listed it stays idle either way. See [pipeline.md](pipeline.md) |
| `pipeline verify <count> [dimension]` | compares worker-shaped and reference terrain for the dimension's generator, when that class can be copied. 10000 clean chunks records a pass. The dimension must be loaded. See [pipeline.md](pipeline.md) |

## Hotspots

| Command | Does |
| --- | --- |
| `hotspots list` | hotspots in your dimension: id, chunk and block coordinates, source (bed, dwell, manual), ring progress or done, chunks generated and populated, minutes since last worked; sorted by distance from you |
| `hotspots here` | dwell in your cell and the 3x3 around it against the threshold, whether a hotspot is within the merge radius, and the five cells with the most dwell (for tuning `dwellMinutes`) |
| `hotspots add [x z]` | adds a manual hotspot at your chunk or the given chunk coordinates (merged into an existing one within `mergeRadiusChunks`) |
| `hotspots remove <id>` | removes the record and the dwell counters around it, so the same stay does not create it again. Generated chunks are kept |
| `hotspots reset <id>` | restarts the hotspot's ring progress; existing chunks are only loaded and skipped |

Tab completion covers the subcommands.
