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
}
