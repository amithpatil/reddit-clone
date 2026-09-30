package com.redditclone.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record FlairRequest(
        @NotBlank @Size(max = 64) String text,
        @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color,
        @NotNull @Pattern(regexp = "user|post") String type
) {
}
