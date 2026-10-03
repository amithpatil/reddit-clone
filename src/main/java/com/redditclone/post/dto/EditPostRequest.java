package com.redditclone.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EditPostRequest(@NotBlank @Size(max = 40000) String body) {
}
