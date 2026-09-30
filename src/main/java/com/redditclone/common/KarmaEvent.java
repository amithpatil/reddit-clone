package com.redditclone.common;

import java.util.UUID;

/**
 * A non-zero karma change caused by one post or comment's vote delta, returned by PostService/
 * CommentService.applyVoteDeltas so the vote module can attribute it to an author (across the module
 * boundary, post/comment repositories stay private to their own modules — see ModuleBoundaryTest) and
 * append it to karma_log without either module needing to know about users or karma_log directly.
 */
public record KarmaEvent(UUID userId, int delta, UUID sourceId) {
}
