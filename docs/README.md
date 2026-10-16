# Chunk Steward — Architecture

Server-side performance mod (`chunksteward`) for 1.12.2 / CleanroomMC. Smooths chunk loading and generation so bursts do not cause tick lag. It spreads work across ticks; it cannot split the cost of one heavy chunk, and it does not make worldgen async (populate and every `IWorldGenerator` write to the live world).

Formerly Lazy Chunks 1.12 (modid `lazychunks`); renamed to Chunk Steward (modid `chunksteward`) when the scope grew past chunk loading. Credits and licence notes: [../NOTICE.md](../NOTICE.md).

Behavior modeled on the MIT-licensed Modrinth mod Lazy Chunk Load (1.20.1+). Code is written from scratch.

**Always compile as Java 21** (`options.release = 21`, class major 65), even on the JDK 25 toolchain. See [build-and-release.md](build-and-release.md).

| System | Doc |
| --- | --- |
| Adaptive throttle, emergency bypass (speed-scaled), warmup, save pause | [throttle.md](throttle.md) |
| Direction-weighted chunk order, re-sort on turning | [ordering.md](ordering.md) |
| Preload: idle and moving tiers, terrain-only ring | [idle-preload.md](idle-preload.md) |
| Populate budgeting, cascade handling | [populate.md](populate.md) |
| Random-tick guard | [random-ticks.md](random-ticks.md) |
| Stage gate: populate suppression, light deferral | [stage-gate.md](stage-gate.md) |
| Status log metrics | [metrics.md](metrics.md) |
| Build / deploy | [build-and-release.md](build-and-release.md) |
| Smoke tests | [verification.md](verification.md) |
| Changelog | [changelog.md](changelog.md) |

## Mixins (`mixins.chunksteward.early.json`, required, `defaultRequire: 1`, in jar manifest `MixinConfigs`)

| Mixin | Target (SRG) | Purpose |
| --- | --- | --- |
| `MixinPlayerChunkMap` | `@Redirect` of `PlayerChunkMapEntry.func_187268_a(Z)Z` inside `PlayerChunkMap.func_72693_b` | throttle gate |
| `MixinPlayerChunkMap` | `@Redirect` of `Collections.sort(List, Comparator)` ordinal 0 inside `func_72693_b` | direction-weighted order |
| `AccessorPlayerChunkMap` | `field_187312_l` (set), `field_187311_h` (get) | re-sort flag, waiting count |
| `MixinPlayerChunkMapEntry` | `<init>(PlayerChunkMap, int, int)` RETURN | wait-time metric |
| `MixinChunk` | `func_186034_a(IChunkGenerator)` HEAD (cancellable) / RETURN | stage gate (populate), populate depth, populate timing |
| `MixinChunk` | `func_150809_p()` HEAD (cancellable) / RETURN | stage gate (light), light timing |
| `MixinChunkProviderServer` | `func_186025_d` HEAD (cancellable) | random-tick guard (empty chunk) |
| `MixinChunkProviderServer` | `func_186025_d` at `IChunkGenerator.func_185932_a` INVOKE, before and after | terrain timing, cascade detection, known-generated cache |
| `MixinWorldServer` | `func_147456_g` HEAD/RETURN | brackets the random-tick pass |
| `AccessorPlayerChunkMap` | `field_72698_e` (get) | view distance for preload radii |
| `MixinGameRegistry` | `@Redirect` of `IWorldGenerator.generate` inside Forge `GameRegistry.generateWorld` (`remap = false`) | per-generator timing |
| `MixinMinecraftServer` | `func_71217_p` HEAD/RETURN, `func_71267_a` HEAD | tick timing, save notice |

`Chunk.onTick` is not hooked: Somnia's coremod rewrites its `checkLight()` call into `SomniaUtil.chunkLightCheck(Chunk)` (overwritten by Arcana Quest Tweaks with a 20-tick throttle for distant chunks), and a redirect there failed at boot in 0.2's first build.

Annotations use MCP names; `remapJar` writes the SRG names into `mixins.chunksteward.refmap.json`. State lives in `server/`, never in mixin classes. Not touched: `World.checkLight` (the Arcana Quest Tweaks mod owns a mixin there) and the `checkLight` call inside populate.

Chunk peeks use `ChunkProviderServer.loadedChunks` (`StageGate.peek`), never `getLoadedChunk`: `func_186026_b` clears `Chunk.unloadQueued` and so cancels a pending unload.

Config: `config/chunksteward.cfg` (`ChunkStewardConfig`).
