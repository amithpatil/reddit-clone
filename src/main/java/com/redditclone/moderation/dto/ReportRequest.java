package com.redditclone.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record ReportRequest(
        @NotNull @Pattern(regexp = "post|comment") String targetType,
        @NotNull UUID targetId,
        @NotBlank String reason
) {
}
