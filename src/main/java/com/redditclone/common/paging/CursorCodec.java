package com.redditclone.common.paging;

import com.redditclone.common.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque cursor encoding for keyset pagination: base64("epochMillis:uuid").
 * Simpler than a literal Reddit fullname (t3_...) since no DB lookup is needed to resume a page —
 * both fields the ORDER BY needs are carried in the token itself.
 */
public final class CursorCodec {

    private CursorCodec() {
    }

    public static Cursor decode(String after) {
        if (after == null || after.isBlank()) {
            return Cursor.FIRST_PAGE;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(after), StandardCharsets.UTF_8);
            // epochSecond:nanoAdjustment:uuid — see encode() for why this isn't epochMillis.
            String[] parts = decoded.split(":", 3);
            Instant createdAt = Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
            UUID id = UUID.fromString(parts[2]);
            return new Cursor(createdAt, id);
        } catch (Exception e) {
            throw new BadRequestException("invalid cursor");
        }
    }

    // epochSecond+nano rather than toEpochMilli(): the latter truncates to millisecond precision, but
    // posts.created_at is a microsecond-precision TIMESTAMPTZ, so a millis-truncated cursor can compare
    // as neither "<" nor "=" against a row whose true timestamp falls in the truncated sub-millisecond
    // gap — silently and permanently skipping that row on every later page.
    public static String encode(Instant createdAt, UUID id) {
        String raw = createdAt.getEpochSecond() + ":" + createdAt.getNano() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
