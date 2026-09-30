package com.redditclone.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ModMailRequest(
        @NotBlank @Size(max = 10000) String body
) {
}
