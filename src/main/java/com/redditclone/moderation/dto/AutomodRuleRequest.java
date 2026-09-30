package com.redditclone.moderation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

public record AutomodRuleRequest(
        @NotNull @Pattern(regexp = "keyword|regex|karma_threshold") String ruleType,
        @NotNull Map<String, Object> config,
        @NotNull @Pattern(regexp = "remove|report") String action
) {
}
