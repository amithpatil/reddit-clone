package com.redditclone.moderation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddModeratorRequest(
        @NotNull UUID userId,
        @NotNull Integer permissions
) {
}
