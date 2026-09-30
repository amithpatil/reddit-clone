package com.redditclone.message.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ComposeRequest(
        @NotBlank String recipientUsername,
        @Size(max = 200) String subject,
        @NotBlank @Size(max = 10000) String body
) {
}
