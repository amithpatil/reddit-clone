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
            int sep = decoded.indexOf(':');
            long epochMillis = Long.parseLong(decoded.substring(0, sep));
            UUID id = UUID.fromString(decoded.substring(sep + 1));
            return new Cursor(Instant.ofEpochMilli(epochMillis), id);
        } catch (Exception e) {
            throw new BadRequestException("invalid cursor");
        }
    }

    public static String encode(Instant createdAt, UUID id) {
        String raw = createdAt.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
