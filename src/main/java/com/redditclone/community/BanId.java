package com.redditclone.community;

import java.io.Serializable;
import java.util.UUID;

public record BanId(UUID communityId, UUID userId) implements Serializable {
}
