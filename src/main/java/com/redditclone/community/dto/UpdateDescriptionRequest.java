package com.redditclone.community.dto;

import jakarta.validation.constraints.Size;

public record UpdateDescriptionRequest(@Size(max = 2000) String description) {
}
