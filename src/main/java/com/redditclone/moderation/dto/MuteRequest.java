package com.redditclone.moderation.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record MuteRequest(
        @NotNull UUID userId,
        String reason,
        Instant expiresAt
) {
}
