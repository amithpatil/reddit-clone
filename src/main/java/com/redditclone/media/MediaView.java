package com.redditclone.media;

import java.math.BigDecimal;

// Built from app.media.public-base-url + the stored keys — never the raw r2Key (the original untouched
// upload, kept for audit/reprocessing) so a client only ever sees the processed, servable variants.
public record MediaView(String thumbnailUrl, String displayUrl, Integer width, Integer height,
                         BigDecimal durationSeconds, String processingStatus) {
}
