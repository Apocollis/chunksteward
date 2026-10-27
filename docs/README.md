# Chunk Steward — Architecture

Server-side performance mod (`chunksteward`) for 1.12.2 / CleanroomMC. Smooths chunk loading and generation so bursts do not cause tick lag. It spreads work across ticks; it cannot split the cost of one heavy chunk. A dimension's own chunk generator can move off the server thread after a verify pass for that class ([pipeline.md](pipeline.md)). RTG is included. A worker or verify thread uses that generator's random, locks each shared terrain and surface call, and keeps a private cellular cache. The server thread still uses the shared `RTGWorld`. Populate and every `IWorldGenerator` stay on the server thread.

Formerly Lazy Chunks 1.12 (modid `lazychunks`); renamed to Chunk Steward (modid `chunksteward`) when the scope grew past chunk loading. Credits and licence notes: [../NOTICE.md](../NOTICE.md).

Behavior modeled on the MIT-licensed Modrinth mod Lazy Chunk Load (1.20.1+). Code is written from scratch.

**Requirements:** CleanroomMC (Cleanroom loader with MixinBooter/CleanMix) on Java 21. The jar is Java 21 bytecode and is marked as a Cleanroom mod, so Forge on Java 8 is not supported. Licence: MIT ([../LICENSE](../LICENSE)).

**Always compile as Java 21** (`options.release = 21`, class major 65), even on the JDK 25 toolchain. See [build-and-release.md](build-and-release.md).

| System | Doc |
| --- | --- |
| Adaptive throttle, emergency bypass (speed-scaled), warmup, save pause | [throttle.md](throttle.md) |
| Direction-weighted chunk order, re-sort on turning | [ordering.md](ordering.md) |
| Preload: idle and moving tiers, terrain-only ring | [idle-preload.md](idle-preload.md) |
| Populate budgeting, cascade handling | [populate.md](populate.md) |
| Stage 2 home-area pregen: hotspots, dwell, saved data | [stage2.md](stage2.md) |
| Operator commands | [commands.md](commands.md) |
| Pacing API for other mods | [api.md](api.md) |
| Random-tick guard | [random-ticks.md](random-ticks.md) |
| Stage gate: populate suppression, light deferral | [stage-gate.md](stage-gate.md) |
| Status log metrics | [metrics.md](metrics.md) |
| Terrain pipeline (no generation in this build) | [pipeline.md](pipeline.md) |
| Temporary diagnostics (`dbg` lines) | [diagnostics.md](diagnostics.md) |
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
| `MixinMapGenBase` | `MapGenBase.func_186125_a` HEAD/RETURN | terrain-stage map-gen timing by class (caves, ravines, structure layout) |
| `MixinMapGenStructure` | `MapGenStructure.func_175794_a` HEAD/RETURN | structure paste timing by class (inside populate) |
| `MixinMapGenStructure` | adds `MapGenBase.generate` | pipeline workers skip structure layout, except Atum's `MapGenPyramid` (matched by class name) |
| `MixinChunkGeneratorHell` / `MixinChunkGeneratorEnd` | `generateChunk` HEAD, cancellable | install a worker-built vanilla nether or end chunk |
| `mixin/dims/*` | late, each dimension generator's `func_185932_a` HEAD | install a worker chunk for NetherEx, Nether API, Aurorian, Atum, Twilight Forest, Aether, the Beneath |
| `MixinWorldPipeline` | `getBiomeProvider` / `getPerWorldStorage` / `getSeaLevel` HEAD | worker's biome provider when the world's dimension matches the job; scratch structure save for workers and verify; Nether API's per-thread sea level |
| `MixinWorldProviderPipeline` | `WorldProvider.getBiomeProvider` HEAD | the same dimension-checked worker biome provider, for generators that ask the provider |
| `MixinIntCache` | `@Overwrite` of `IntCache.getIntCache` / `resetIntCache` / `getCacheSizes` | one cache per thread (vanilla's is one static pool, `synchronized`, reset by each user) |

## Late mixins (one config per mod, `required: false`)

`ChunkStewardLateMixinLoader` queues each config only when a mod jar contains its probe class; a source it cannot read is skipped. The generator mods are compile-only (`libs/mods`), so a pack without one of them only loses the pipeline for that generator.

| Config | Mixins | Purpose |
| --- | --- | --- |
| `netherapi` | `MixinChunkGeneratorNether`, `MixinChunkGeneratorTheEnd`, `MixinAbstractNetherExBiome` | worker chunks; the end generator's `recreateStructures` also records the end city starts (Nether API's override drops vanilla's call); NetherEx biomes' `buildSurface` sets sea level on a per-thread, per-world value (`ThreadSeaLevel`, `@WrapMethod` with `finally`) instead of the shared `World` |
| `endexpansion` | `MixinBiomeBarrendLands` (package `mixin.endexpansion`) | Barrend `buildSurface` takes `nextInt` from the generator's random, not the biome's shared one |
| `aurorian` | `MixinAurorianChunkGenerator` | reseeds the generator random from the chunk position in `generateChunk` and `populate`, so order does not matter |
| `betweenlands` | `MixinBiomeGenerator` and the nine noise features | noise buffers (reads only; Mixin cannot redirect an array-field `PUTFIELD`) and the "noise generated" flag per thread (`ThreadNoise`); first `initializeGenerators` per thread runs under the generator's lock |
| `rtg` | `MixinChunkGeneratorRTG`, `MixinLatePopulatingChunk`, `MixinRTGWorld`, `MixinRealisticBiomeBase`, `MixinTraverseStep`, `MixinSpacedCellularNoise` | worker chunks; on a worker or verify thread, `RTGWorld.rand()` is that generator copy's `Random`, `generateNoise` and `paintTerrain` run one thread at a time per instance, and each thread has its own cellular cache |
| `twilight` | `MixinChunkGeneratorTwilightForest`, `MixinChunkGeneratorTwilightVoid`, `MixinTFBiomeHighlands`, `MixinTFBiomeBase` | worker chunks; the highlands biome's per-column top and filler block are held per thread (`ThreadSurfaceBlocks`) instead of in the shared biome fields |
| `atum` | `MixinChunkGeneratorAtum` | clones the 16×16 surface biomes after `getBiomes` and copies them back before `new Chunk`, so pyramid ground sampling's 10×10 write does not reach the chunk |
| `MixinChunk` | `IChunkGenerator.func_185931_b` INVOKE, before and after, inside `func_186034_a` | generator populate timing (decoration and structure paste, apart from mod world generators) |
| `MixinMinecraftServer` | `func_71217_p` HEAD/RETURN, `func_71267_a` HEAD | tick timing, save notice |

`Chunk.onTick` is not hooked: Somnia's coremod rewrites its `checkLight()` call into `SomniaUtil.chunkLightCheck(Chunk)` (overwritten by Arcana Quest Tweaks with a 20-tick throttle for distant chunks), and a redirect there failed at boot in 0.2's first build.

Annotations use MCP names; `remapJar` writes the SRG names into `mixins.chunksteward.refmap.json`. State lives in `server/`, never in mixin classes. Not touched: `World.checkLight` (the Arcana Quest Tweaks mod owns a mixin there) and the `checkLight` call inside populate.

Chunk peeks use `ChunkProviderServer.loadedChunks` (`StageGate.peek`), never `getLoadedChunk`: `func_186026_b` clears `Chunk.unloadQueued` and so cancels a pending unload.

Config: `config/chunksteward.cfg` (`ChunkStewardConfig`).
