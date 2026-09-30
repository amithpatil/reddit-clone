package com.redditclone.moderation;

import java.io.Serializable;
import java.util.UUID;

public record ModMailMuteId(UUID communityId, UUID userId) implements Serializable {
}
