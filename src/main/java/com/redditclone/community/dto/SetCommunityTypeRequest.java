package com.redditclone.community.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record SetCommunityTypeRequest(
        @NotNull @Pattern(regexp = "public|restricted|private") String type
) {
}
