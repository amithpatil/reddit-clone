package com.redditclone.common.paging;

import com.redditclone.common.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque cursor encoding for rank-based keyset pagination: base64("sort:rank:uuid[:anchorEpochSecond]").
 * Double.toString/parseDouble round-trip exactly (the JLS guarantees Double.toString produces the
 * shortest decimal that parses back to the same bits), so no precision is lost across an encode/decode
 * cycle.
 *
 * The leading `sort` tag ties a cursor to the endpoint that minted it (hot/top/rising/controversial):
 * without it, a token from one rank column silently produces a wrong or truncated page if fed into a
 * different /r/{name}/{sort} endpoint (e.g. a hot_rank value reinterpreted as an integer score), with no
 * error to signal it. decode() rejects a cursor whose tag doesn't match the endpoint using it.
 */
public final class RankCursorCodec {

    private RankCursorCodec() {
    }

    public static RankCursor decode(String after, String expectedSort) {
        if (after == null || after.isBlank()) {
            return RankCursor.FIRST_PAGE;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(after), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 4);
            if (!expectedSort.equals(parts[0])) {
                throw new BadRequestException("invalid cursor");
            }
            double rank = Double.parseDouble(parts[1]);
            UUID id = UUID.fromString(parts[2]);
            Long anchorEpochSecond = parts.length > 3 ? Long.parseLong(parts[3]) : null;
            return new RankCursor(rank, id, anchorEpochSecond);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("invalid cursor");
        }
    }

    public static String encode(String sort, double rank, UUID id, Long anchorEpochSecond) {
        String raw = sort + ":" + rank + ":" + id + (anchorEpochSecond != null ? ":" + anchorEpochSecond : "");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
