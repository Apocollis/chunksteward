# Verification

## 0.2

| Check | How | Status |
| --- | --- | --- |
| Build, class major 65, refmap present, version match | `.\gradlew.bat build` (`verifyReleaseJar`) | passed |
| Refmap resolves to the SRG names in the 0.5.7-alpha searge jar | `func_186034_a`, `func_150809_p`, `func_150804_b`, `func_185932_a`, `func_186025_d`, `field_187312_l`, `field_187311_h` checked against `javap` of the searge jar | passed |
| DEVBOX boots, no mixin errors (VintageFix, LoliASM present) | start instance, read log for `lazychunks` mixin errors or "already loaded" warnings (`GameRegistry`) | first build failed: `onTick` light redirect found 0 targets (Somnia rewrites `Chunk.onTick`). Gate moved to `checkLight()` HEAD; rebuilt, not yet booted |
| Order follows view | penalty 1.0, turn in place in fresh terrain: missing chunks fill in front first; re-sort within ~4 ticks of a 30 degree turn | not run |
| Penalty 0 = vanilla | `directionPenalty = 0`: fill order is concentric as in 0.1 | not run |
| Walk forward | chunks ahead arrive before sides/behind; `waiting` and wait avg in log | not run |
| Speed-scaled emergency radius | fly elytra / ride with `statusIntervalSeconds = 5`: max emergency radius 2–5 while flying, base when walking | not run |
| Terrain-only ring | stand still on a **copy** of a world: preloaded + terrain-only counts rise, populate deferred > 0; walk to the ring: chunks populate (trees, ores) and appear with no holes; `populate resumed` counts chunks claimed while loaded | not run |
| No chunk leak | after preload, loaded chunk count returns to baseline (`/forge tps` or spark) | not run |
| Stage timing | log line 2 shows terrain/populate/light and top generators; numbers plausible against spark | not run |
| Light deferral off vs on | `deferEnabled = true`, fast travel: light deferred/drained counts, no unlit chunks, edge chunks arrive slightly later; queue returns to 0 | not run |
| Spark profile | same ~286 s profile vs 0.1 baseline | not run |

## 0.1

| Check | How | Status |
| --- | --- | --- |
| Build, class major 65, refmap present | `.\gradlew.bat build` | passed |
| Refmap resolves to the SRG names in the 0.5.7-alpha searge jar | inspect `mixins.lazychunks.refmap.json` | passed |
| DEVBOX boots, no mixin errors | start instance, read log | passed (runs on DEVBOX) |
| Throttle defers chunks when busy | `statusIntervalSeconds = 5`, fast travel, read log | not run |
| Idle full speed, starvation guard | tick EMA below `idleTickMs`; busy server still gets a chunk per 4 ticks | not run |
| Emergency bypass | teleport into an ungenerated area, player chunk loads at once | not run |
| Warmup | join, first 10 s load unthrottled | not run |
| Save pause | autosave tick does not generate | not run |
| Idle preload | stand still, chunks fill in; move, it stops; no chunk stays loaded after `holdTicks` | not run |
| Spark profile | same ~286 s profile vs baseline (57% idle, ~9% chunk provision) | not run |
