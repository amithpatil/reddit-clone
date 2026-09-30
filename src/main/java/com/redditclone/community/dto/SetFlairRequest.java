package com.redditclone.community.dto;

import java.util.UUID;

// flairId is nullable by design — null clears the flair. Reused by self-assign and both mod-assign
// endpoints rather than three near-identical DTOs.
public record SetFlairRequest(UUID flairId) {
}
