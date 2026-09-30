package com.redditclone.common.paging;

import java.util.UUID;

/**
 * Keyset cursor for a listing sorted by a precomputed rank column (hot_rank, score, controversial_rank,
 * rising_rank, best_rank) DESC with id DESC as the tiebreaker — same shape as Cursor, but keyed on a
 * double rank value instead of created_at, since these sorts don't order by time.
 */
public record RankCursor(double rank, UUID id) {

    // Double.MAX_VALUE sorts above every real rank value, so this matches every row on the first page,
    // same role as Cursor.FIRST_PAGE.
    public static final RankCursor FIRST_PAGE = new RankCursor(
            Double.MAX_VALUE,
            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
    );
}
