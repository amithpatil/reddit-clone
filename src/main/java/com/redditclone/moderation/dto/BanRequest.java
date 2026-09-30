package com.redditclone.moderation.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

// expiresAt null = permanent ban; set = temporary/timeout — same convention BanRequest and MuteRequest
// both use, matching community.Ban's own single-mechanism-by-expiry design.
public record BanRequest(
        @NotNull UUID userId,
        String reason,
        Instant expiresAt
) {
}
