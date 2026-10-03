package com.redditclone.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EditCommentRequest(@NotBlank @Size(max = 10000) String body) {
}
