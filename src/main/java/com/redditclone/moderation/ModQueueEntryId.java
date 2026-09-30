package com.redditclone.moderation;

import java.io.Serializable;
import java.util.UUID;

public record ModQueueEntryId(UUID communityId, String targetType, UUID targetId) implements Serializable {
}
