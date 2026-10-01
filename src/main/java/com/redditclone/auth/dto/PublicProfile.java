package com.redditclone.auth.dto;

import com.redditclone.auth.User;

import java.time.Instant;
import java.util.UUID;

public record PublicProfile(UUID id, String username, int karmaPost, int karmaComment, Instant createdAt,
                             String status, int followerCount, int followingCount, Boolean isFollowing) {

    // karmaPost + karmaComment come straight off the users row — they're updated by the outbox worker
    // that applies score deltas (Phase 2), never computed live from karma_log.
    // status ("active"/"banned"/"deleted") is exposed so the frontend can show a suspended/deleted banner,
    // matching real Reddit's own profile pages — not sensitive, and already publicly inferable from the
    // account being unable to post/comment.
    // id (F8): lets the moderation dashboard resolve a typed username to the id POST /mod/ban actually
    // needs, via this same already-public endpoint — not sensitive, already publicly inferable one hop
    // away from any post/comment the user has ever made (Post.authorId/Comment.authorId are no secret).
    // followerCount/followingCount (feature 10): same denormalized-counter convention as karmaPost/
    // karmaComment, maintained by follow.FollowService, never a live COUNT(*).
    // isFollowing (feature 10): null = not computed for this viewer (anonymous request, or a call site that
    // doesn't need it) — same "null means not computed, not false" convention as Community.isMember.
    public static PublicProfile from(User user) {
        return from(user, null);
    }

    public static PublicProfile from(User user, Boolean isFollowing) {
        return new PublicProfile(user.getId(), user.getUsername(), user.getKarmaPost(), user.getKarmaComment(),
                user.getCreatedAt(), user.getStatus(), user.getFollowerCount(), user.getFollowingCount(), isFollowing);
    }
}
