package com.redditclone.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCommunityRequest(
        @NotBlank @Size(min = 3, max = 32) String name,
        @Size(max = 2000) String description,
        @Pattern(regexp = "public|restricted|private") String type
) {
}
