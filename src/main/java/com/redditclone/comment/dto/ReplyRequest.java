package com.redditclone.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ReplyRequest(
        @NotNull UUID postId,
        UUID parentId,
        @NotBlank @Size(max = 10000) String body
) {
}
