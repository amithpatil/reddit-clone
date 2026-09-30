package com.redditclone.engagement;

import java.io.Serializable;
import java.util.UUID;

public record HiddenItemId(UUID userId, String targetType, UUID targetId) implements Serializable {
}
