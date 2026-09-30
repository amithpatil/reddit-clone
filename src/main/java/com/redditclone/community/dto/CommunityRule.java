package com.redditclone.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommunityRule(
        @NotBlank @Size(max = 100) String title,
        @Size(max = 500) String description
) {
}
