# Build and release

- `.\build_gradle.ps1` — `gradlew build`, then deletes old `LazyChunks-*.jar` and copies the new one to `mods/` and the DEVBOX mods folder (`LAZYCHUNKS_DEVBOX_MODS`, `LAZYCHUNKS_JAVA_HOME` override the defaults).
- `.\gradlew.bat build` — compile only. `options.release = 21` must stay; `verifyReleaseJar` fails on any class major other than 65 and checks the refmap, the early json and that `LazyChunks.VERSION` matches `build.gradle`.
- Version lives in `build.gradle` (`version`) and `LazyChunks.VERSION`.
