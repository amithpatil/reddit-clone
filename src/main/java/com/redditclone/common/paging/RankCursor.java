package com.redditclone.common.paging;

import java.util.UUID;

/**
 * Keyset cursor for a listing sorted by a precomputed rank column (hot_rank, score, controversial_rank,
 * rising_rank, best_rank) DESC with id DESC as the tiebreaker — same shape as Cursor, but keyed on a
 * double rank value instead of created_at, since these sorts don't order by time.
 *
 * anchorEpochSecond is null for every sort except /top: it carries the "now" instant /top's period
 * filter (t=hour|day|...) was computed against on page 1, so later pages reuse the same cutoff instead
 * of each request recomputing Instant.now() and getting a silently different (and possibly
 * skipping/duplicating) window.
 */
public record RankCursor(double rank, UUID id, Long anchorEpochSecond) {

    // Double.MAX_VALUE sorts above every real rank value, so this matches every row on the first page,
    // same role as Cursor.FIRST_PAGE.
    public static final RankCursor FIRST_PAGE = new RankCursor(
            Double.MAX_VALUE,
            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
            null
    );
}
