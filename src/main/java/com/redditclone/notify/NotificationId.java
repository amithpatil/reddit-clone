package com.redditclone.notify;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record NotificationId(UUID id, Instant createdAt) implements Serializable {
}
