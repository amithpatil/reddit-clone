package com.redditclone.engagement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TargetRequest(
        @NotBlank String targetType,
        @NotNull UUID targetId
) {
}
