package com.redditclone.community.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ApprovedSubmitterRequest(@NotNull UUID userId) {
}
