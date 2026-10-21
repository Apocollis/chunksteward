# Build and release

- `.\build_gradle.ps1` — `gradlew build`, then deletes old `ChunkSteward-*.jar` and copies the new one to `mods/` and the DEVBOX mods folder (`CHUNKSTEWARD_DEVBOX_MODS`, `CHUNKSTEWARD_JAVA_HOME` override the defaults).
- `.\gradlew.bat build` — compile only. `options.release = 21` must stay; `verifyReleaseJar` fails on any class major other than 65 and checks the refmap, the early json and that `ChunkSteward.VERSION` matches `build.gradle`.
- Runtime requirement: CleanroomMC with MixinBooter/CleanMix on Java 21 (class major 65, `ModType: CRL`). Not compatible with Forge on Java 8: that would need a Java 8 release target, a Java 8 mixin compatibility level, no `StackWalker` in `RandomTickGuard`, a different Gradle setup and a retest.
- Optional late mixin: `mixins.chunksteward.rtg.json` (package `mixin.rtg`, `required: false`) is queued by `ChunkStewardLateMixinLoader` only when RTG-Plus is installed; `verifyReleaseJar` checks the json, the loader class and the refmap entry for the `doPopulate` generator invoke.
- Licence: MIT, `LICENSE` in the repo root.
- Version lives in `build.gradle` (`version`) and `ChunkSteward.VERSION`.
