# Build and release

- `.\build_gradle.ps1` — `gradlew build`, then deletes old `ChunkSteward-*.jar` and copies the new one to `mods/` and the DEVBOX mods folder (`CHUNKSTEWARD_DEVBOX_MODS`, `CHUNKSTEWARD_JAVA_HOME` override the defaults).
- `.\gradlew.bat build` — compile only. `options.release = 21` must stay; `verifyReleaseJar` fails on any class major other than 65 and checks the refmap, the early json and that `ChunkSteward.VERSION` matches `build.gradle`.
- Runtime requirement: CleanroomMC with MixinBooter/CleanMix on Java 21 (class major 65, `ModType: CRL`). Not compatible with Forge on Java 8: that would need a Java 8 release target, a Java 8 mixin compatibility level, no `StackWalker` in `RandomTickGuard`, a different Gradle setup and a retest.
- Optional late mixin: `mixins.chunksteward.rtg.json` (package `mixin.rtg`, `required: false`) is queued by `ChunkStewardLateMixinLoader` only when RTG-Plus is installed; `verifyReleaseJar` checks the json, the loader class and the refmap entry for the `doPopulate` generator invoke.
- Generator mod jars (Atum, Nether API, NetherEx, End Expansion, the Aurorian, the Betweenlands, LibraryEx, Arcana Quest Tweaks) are compile-only: copy them into `libs/mods` (git-ignored). Each is declared as a `modCompileOnly` flatDir module so unimined remaps it to MCP names ("Found 8 mods for remapping"); a `fileTree` gives "No mods found". None are shipped. Their mixins live in per-mod late configs (`netherex`, `netherapi`, `endexpansion`, `aurorian`, `atum`, `betweenlands`, …), each checked by `verifyReleaseJar`. That task also fails if a class outside `mixin/dims`, `mixin/endexpansion`, `mixin/rtg` and `ChunkStewardLateMixinLoader` names one of those mods' packages; elsewhere look them up by name with reflection.
- Licence: MIT, `LICENSE` in the repo root.
- Version lives in `build.gradle` (`version`) and `ChunkSteward.VERSION`.

## Agent / development workflow

1. **Investigate read-only first:** Never edit code, configs, or build files without prior approval.
2. **Plan:** Present an implementation plan under `/.plans/YYYYMMDD-HHmm-<kebab-task>.md` (never repo-root `implementation_plan.md`) covering the goal, proposed file changes, risks/decisions, and verification steps.
3. **Wait for approval:** STOP and wait for explicit `proceed` before making changes.
4. **Implement & verify:** Implement approved changes, then run `.\build_gradle.ps1`. A finished build deploys `ChunkSteward-<version>.jar` to the repo `mods/` folder and the DEVBOX mods folder. `.\gradlew.bat build` does not. The dedicated server is a separate copy.
5. **Plan Archive Policy (never delete):**
   - When a plan in `/.plans/YYYYMMDD-HHmm-<task>.md` is completed, superseded, or resolved (including issues resolved via config or alternative workarounds), **DO NOT delete the plan file**.
   - Instead, update the file's header status (e.g., `**Status:** Completed (YYYY-MM-DD)` or `**Status:** Archived (Resolved via config/superseded...)`) with a brief resolution summary.
   - Move the file to `/.plans/archive/`.
   - The root `/.plans/` folder must contain only active, in-progress work, keeping the backlog clear while permanently preserving historical investigations, design alternatives, and negative knowledge in `/.plans/archive/`.

