package com.redditclone.media;

import java.util.UUID;

// A row claimed by MediaService.claimUploadedBatch — plain data, not a managed JPA entity (the claim
// query is raw SQL via UPDATE ... RETURNING, and the later result-write step re-reads by id in its own
// transaction rather than trying to save() a manually-built detached entity).
public record ClaimedMedia(UUID id, UUID ownerId, String mediaType, String r2Key, String contentType,
                            long byteSize, int attemptCount, String correlationId) {
}
