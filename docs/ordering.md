# Direction-weighted chunk order

Vanilla `PlayerChunkMap.tick` sorts `entriesWithoutChunks` (`field_187311_h`) by squared distance to the closest watching player (`PlayerChunkMap$4`, `getClosestPlayerDistance`). It sorts only when `sortMissingChunks` (`field_187312_l`) is set, and only on ticks where world total time % 4 == 0. The flag is set when a player changes chunk. The first `Collections.sort` in the method is redirected to `DirectionalOrder.sort`; the second (`$5`, pending sends) is untouched.

## Score

For each watching player: `distSq * (1 + directionPenalty * (1 - cos a) / 2)^2`, where `distSq` is vanilla's (chunk centre to player, horizontal) and `a` is the angle between the player→chunk vector and the player's direction. Straight ahead costs `distSq`; straight behind costs `distSq * (1 + penalty)^2`, i.e. behind counts as `1 + penalty` times as far. Nothing is excluded. With several players the lowest score wins. The chunk under the player scores 0.

Scores are computed once per sort into an array and the sort compares the cached doubles (`Double.compare`), so the order is a fixed total order and TimSort's contract holds even while players move. The sort is stable, like vanilla's `Collections.sort`. `directionPenalty = 0` or `enabled = false` calls `Collections.sort` with vanilla's own comparator: vanilla order exactly.

Players are walked with `PlayerChunkMapEntry.hasPlayerMatching` and a predicate that never matches (it only records the best score). `getWatchingPlayers` cannot be used: it returns an empty list until the chunk has been sent.

## Direction (`PlayerMotion`)

Sampled at server tick END every `movement.sampleTicks` (10) ticks from the position change since the last sample (blocks per tick, smoothed with weight 0.6 for the newest sample); between samples the values are held, so they are at most half a second old. A jump over 4 blocks per elapsed tick (at least 10 blocks) or a dimension change resets the velocity. Below `velocityMinSpeed` (0.35 b/t) the direction is the view yaw; at or above `velocityFullSpeed` (0.8) it is the travel direction; in between the two unit vectors are blended and normalised. So turning the head while walking reorders, and an elytra flight looking sideways still loads ahead of the flight path.

## Re-sort on turning

When a player's direction turns more than `resortAngleDegrees` (30) from the direction at their last trigger, `PlayerMotion` sets `sortMissingChunks` on their world's `PlayerChunkMap` through `AccessorPlayerChunkMap`. The sort still only runs on the next tick divisible by 4. Off when the penalty is 0.

Cost: one score per waiting entry per sort, times the number of players watching it, plus the sort itself. Waiting lists are typically tens to a few hundred entries.
