package com.redditclone.auth.dto;

import com.redditclone.auth.User;

import java.time.Instant;

public record PublicProfile(String username, int karmaPost, int karmaComment, Instant createdAt) {

    // karmaPost + karmaComment come straight off the users row — they're updated by the outbox worker
    // that applies score deltas (Phase 2), never computed live from karma_log.
    public static PublicProfile from(User user) {
        return new PublicProfile(user.getUsername(), user.getKarmaPost(), user.getKarmaComment(), user.getCreatedAt());
    }
}
