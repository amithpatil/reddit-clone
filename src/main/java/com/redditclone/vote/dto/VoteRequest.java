package com.redditclone.vote.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record VoteRequest(
        @NotNull @Pattern(regexp = "post|comment") String targetType,
        @NotNull UUID targetId,
        // Bean Validation's @Min/@Max would also accept 0; direction is a two-valued signal (up/down),
        // never neutral (see Data model — Resolved design decisions), so VoteService checks it's exactly
        // 1 or -1 instead.
        @NotNull Short dir
) {
}
