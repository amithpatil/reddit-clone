package com.redditclone.moderation;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record ReportId(UUID id, Instant createdAt) implements Serializable {
}
