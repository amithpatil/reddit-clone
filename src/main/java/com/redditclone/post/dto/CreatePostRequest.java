package com.redditclone.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreatePostRequest(
        @NotBlank @Pattern(regexp = "text|link|image|video") String kind,
        @NotBlank @Size(max = 300) String title,
        @Size(max = 40000) String body,
        String url
) {
}
