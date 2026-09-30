package com.redditclone.media.dto;

import java.util.UUID;

public record UploadUrlResponse(UUID mediaId, String uploadUrl) {
}
