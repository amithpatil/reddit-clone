package com.redditclone.common.paging;

import java.time.Instant;
import java.util.UUID;

public record Cursor(Instant createdAt, UUID id) {

    // A "real" far-future sentinel rather than Instant.MAX: MAX's epoch-second value overflows a long
    // when Hibernate/pgjdbc convert it to epoch millis for TIMESTAMPTZ binding (verified — it throws
    // ArithmeticException: long overflow). Year 9999 is still centuries past any real row's created_at,
    // well within both Postgres's timestamptz range and a safe long of epoch millis.
    public static final Cursor FIRST_PAGE = new Cursor(
            Instant.parse("9999-12-31T23:59:59Z"),
            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
    );

    // Mirror-image sentinel for an ascending ("oldest first") listing — FIRST_PAGE's year-9999/all-f's
    // value matches everything under a "<" comparison but matches nothing under ">", so an ascending sort
    // needs its own first-page value that's older/lower than any real row instead. Epoch + the all-zeros
    // UUID is a safe "before any real data" floor for both columns. comments' "old" sort (feature 8) is
    // this codebase's first ascending-sorted paginated endpoint.
    public static final Cursor FIRST_PAGE_ASC = new Cursor(
            Instant.EPOCH,
            UUID.fromString("00000000-0000-0000-0000-000000000000")
    );
}
