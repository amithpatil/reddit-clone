package com.redditclone.media;

import com.redditclone.media.dto.UploadUrlRequest;
import com.redditclone.media.dto.UploadUrlResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class MediaController {

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping("/api/media/upload-url")
    public UploadUrlResponse requestUploadUrl(@AuthenticationPrincipal UUID userId, @Valid @RequestBody UploadUrlRequest req) {
        return mediaService.requestUploadUrl(userId, req.filename(), req.contentType(), req.byteSize());
    }

    @PostMapping("/api/media/{id}/complete")
    public void complete(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        mediaService.completeUpload(id, userId);
    }
}
