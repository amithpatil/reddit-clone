package com.redditclone.auth.dto;

import com.redditclone.auth.User;

import java.time.Instant;
import java.util.UUID;

public record UserView(UUID id, String username, String email, int karmaPost, int karmaComment,
                        String status, Instant createdAt) {

    public static UserView from(User user) {
        return new UserView(user.getId(), user.getUsername(), user.getEmail(), user.getKarmaPost(),
                user.getKarmaComment(), user.getStatus(), user.getCreatedAt());
    }
}
