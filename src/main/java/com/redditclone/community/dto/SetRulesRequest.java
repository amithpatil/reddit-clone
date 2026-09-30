package com.redditclone.community.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SetRulesRequest(
        @NotNull @Size(max = 15) List<@Valid CommunityRule> rules
) {
}
