package com.redditclone.common.paging;

import com.redditclone.common.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque cursor encoding for rank-based keyset pagination: base64("rank:uuid"). Double.toString/
 * parseDouble round-trip exactly (the JLS guarantees Double.toString produces the shortest decimal that
 * parses back to the same bits), so no precision is lost across an encode/decode cycle.
 */
public final class RankCursorCodec {

    private RankCursorCodec() {
    }

    public static RankCursor decode(String after) {
        if (after == null || after.isBlank()) {
            return RankCursor.FIRST_PAGE;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(after), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 2);
            double rank = Double.parseDouble(parts[0]);
            UUID id = UUID.fromString(parts[1]);
            return new RankCursor(rank, id);
        } catch (Exception e) {
            throw new BadRequestException("invalid cursor");
        }
    }

    public static String encode(double rank, UUID id) {
        String raw = rank + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
