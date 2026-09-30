package com.redditclone.common;

import java.time.Instant;

/**
 * Reddit's own archived, widely-circulated hot/best/controversial formulas (design inspiration to
 * reproduce Reddit's public behavior, not a confirmed current internal spec) — see the source plan's
 * Detailed class reference, Search & feed caching.
 */
public final class RankFormulas {

    private static final long REDDIT_EPOCH_SECONDS = 1134028003L;
    private static final double WILSON_Z = 1.96; // 95% confidence

    private RankFormulas() {
    }

    public static double hotRank(int score, Instant createdAt) {
        int sign = Integer.compare(score, 0);
        double magnitude = Math.log10(Math.max(Math.abs(score), 1));
        long secondsSinceEpoch = createdAt.getEpochSecond() - REDDIT_EPOCH_SECONDS;
        return sign * magnitude + secondsSinceEpoch / 45000.0;
    }

    // Wilson score lower bound on the upvote ratio (Evan Miller, "How Not to Sort by Average Rating") —
    // outranks a raw-ratio sort because it accounts for sample size: 95 up / 5 down beats 10 up / 0 down.
    public static double bestRank(int ups, int downs) {
        int n = ups + downs;
        if (n == 0) {
            return 0;
        }
        double phat = (double) ups / n;
        double z2 = WILSON_Z * WILSON_Z;
        double left = phat + z2 / (2 * n);
        double right = WILSON_Z * Math.sqrt((phat * (1 - phat) + z2 / (4 * n)) / n);
        return (left - right) / (1 + z2 / n);
    }

    // Reddit's own published controversial formula: high total vote count, weighted toward an even split.
    public static double controversialRank(int ups, int downs) {
        if (ups <= 0 || downs <= 0) {
            return 0;
        }
        double magnitude = ups + downs;
        double balance = ups > downs ? (double) downs / ups : (double) ups / downs;
        return Math.pow(magnitude, balance);
    }
}
