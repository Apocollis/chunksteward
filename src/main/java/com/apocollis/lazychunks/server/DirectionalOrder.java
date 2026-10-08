package com.apocollis.lazychunks.server;

import com.apocollis.lazychunks.config.LazyChunksConfig;
import com.google.common.base.Predicate;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.math.ChunkPos;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Replaces the comparator of the sort of entries without a chunk in {@code PlayerChunkMap.tick}. Vanilla orders by
 * squared distance to the closest watching player. Here each player's squared distance is scaled by
 * {@code (1 + penalty * (1 - cos angle) / 2)^2}, angle measured from that player's direction ({@link PlayerMotion}),
 * so a chunk straight behind counts as {@code 1 + penalty} times as far. The best player wins. Nothing is excluded.
 *
 * <p>Scores are computed once per sort and the sort runs over the cached values, so the order is a consistent total
 * order (TimSort contract). The sort is stable like vanilla's. Penalty 0 calls the vanilla sort unchanged.
 */
public final class DirectionalOrder {

    private static final class Scored {
        PlayerChunkMapEntry entry;
        double score;
    }

    private static final Comparator<Scored> BY_SCORE = (a, b) -> Double.compare(a.score, b.score);

    private static double penalty;
    private static double chunkCenterX;
    private static double chunkCenterZ;
    private static double best;
    private static final double[] DIR = new double[2];

    private static final Predicate<EntityPlayerMP> SCORE_PLAYER = new Predicate<EntityPlayerMP>() {
        @Override
        public boolean apply(EntityPlayerMP player) {
            if (player == null) {
                return false;
            }
            double dx = chunkCenterX - player.posX;
            double dz = chunkCenterZ - player.posZ;
            double distSq = dx * dx + dz * dz;
            double score = distSq;
            if (distSq > 1.0e-6) {
                PlayerMotion.direction(player, DIR);
                double cos = (dx * DIR[0] + dz * DIR[1]) / Math.sqrt(distSq);
                double factor = 1.0 + penalty * (1.0 - cos) * 0.5;
                score = distSq * factor * factor;
            }
            if (score < best) {
                best = score;
            }
            return false;
        }
    };

    private DirectionalOrder() {
    }

    public static void sort(List<PlayerChunkMapEntry> list, Comparator<? super PlayerChunkMapEntry> vanilla) {
        LazyChunksConfig.Ordering cfg = LazyChunksConfig.ordering;
        if (!cfg.enabled || cfg.directionPenalty <= 0.0 || list.size() < 2) {
            Collections.sort(list, vanilla);
            return;
        }
        penalty = cfg.directionPenalty;
        Scored[] scored = new Scored[list.size()];
        for (int i = 0; i < scored.length; i++) {
            PlayerChunkMapEntry entry = list.get(i);
            Scored s = new Scored();
            s.entry = entry;
            s.score = score(entry);
            scored[i] = s;
        }
        Arrays.sort(scored, BY_SCORE);
        for (int i = 0; i < scored.length; i++) {
            list.set(i, scored[i].entry);
        }
    }

    private static double score(PlayerChunkMapEntry entry) {
        ChunkPos pos = entry.getPos();
        chunkCenterX = pos.x * 16 + 8;
        chunkCenterZ = pos.z * 16 + 8;
        best = Double.MAX_VALUE;
        // hasPlayerMatching walks every watching player; the predicate never matches, it only records the best score.
        entry.hasPlayerMatching(SCORE_PLAYER);
        return best;
    }
}
