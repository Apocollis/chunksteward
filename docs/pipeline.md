# Terrain pipeline

Infrastructure for moving terrain generation off the server thread. This build does not generate off-thread. With the default empty `pipeline.allowedGenerators` list, no worker threads start, the CPU guard does not sample, and chunk loading is unchanged.

`ChunkThrottle.provide` is not hooked. Nothing is deferred because a result is waiting.

## When it engages

All three are required:

- `pipeline.enabled` (default true)
- the session kill switch is clear (`/chunksteward pipeline off` sets it; `on` clears it; a session reset clears it)
- `pipeline.allowedGenerators` contains at least one class name

Listing a class starts stub workers (`max(1, min(2, available processors - 2))`, priority one below normal). They park. They do not call a chunk generator. The status text says `generation not started`. `isPipelineActive` stays false.

Workers stop on session reset, on `/chunksteward pipeline off`, and when the list becomes empty or `enabled` is false.

## Config (`pipeline`)

| Key | Default | Role |
| --- | --- | --- |
| `enabled` | true | Master switch. Idle while the generator list is empty. |
| `workers` | 0 | 0 uses the formula above. A set value is clamped to 1 or 2. |
| `maxInFlight` | 16 | Ranking window. |
| `maxReady` | 64 | Cap on results waiting to install. |
| `stealWaitMs` | 10 | Reserved for a later phase. |
| `jobTimeoutSeconds` | 30 | Reserved for a later phase. |
| `maxFailures` | 3 | Reserved for a later phase. |
| `verifyOnStart` | false | Reserved for a later phase. |
| `allowedGenerators` | empty | Class names. Empty means no workers. |
| `speculative` | true | Reserved for a later phase. |
| `readyTtlSeconds` | 120 | Reserved for a later phase. |
| `cpuGuard` | `thread` | `thread`, `system`, or `off`. Samples only while a class is listed. |
| `cpuGuardRatio` | 0.85 | `thread`: server-thread CPU time over tick wall time, below this. |
| `cpuGuardLoad` | 0.90 | `system`: host load above this. |
| `cpuGuardSeconds` | 3 | How long the signal stays past the line before it counts. |
| `cpuGuardResumeSeconds` | 10 | Minimum time between worker-cap steps. |

The thread ratio (CPU time over that tick's wall time) is smoothed per tick, alpha 0.02, about 100 ticks, and judged once a second of tick time. Save-pause ticks are not samples. While the smoothed signal stays hot the cap steps down by one, to 0. At 0, if it is still hot 10 s later, the cap is restored and the guard is ignored for 5 minutes. A clear signal raises the cap by one step. Cap 0 does not defer loading, because this build never defers for the pipeline.

## Command

`/chunksteward pipeline status|off|on`. `off` and `on` last for the session. The periodic status log is unchanged.

## Not in this build

No generator hook, no want-list, no install path, no API version change (`ChunkStewardAPI` stays at 2).
