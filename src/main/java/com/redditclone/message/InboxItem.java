package com.redditclone.message;

import java.time.Instant;

// Reddit's real inbox interleaves private messages and notifications into one feed — kind/data envelope
// mirrors the existing post.Thing<T> convention used for feed listings elsewhere in this codebase.
public record InboxItem(String kind, Instant createdAt, Object data) {
}
