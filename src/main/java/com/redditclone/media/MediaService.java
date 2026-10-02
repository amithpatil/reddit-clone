package com.redditclone.media;

import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.media.dto.UploadUrlResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MediaService {

    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> VIDEO_CONTENT_TYPES = Set.of("video/mp4", "video/quicktime", "video/webm");
    // A GIF's content_type is image/gif, but it's *processed* as video (converted to a muted looping MP4
    // for bandwidth, exactly like real Reddit) — mediaType records that routing decision, distinct from
    // the client-declared contentType.
    private static final String GIF_CONTENT_TYPE = "image/gif";
    private static final Duration UPLOAD_URL_EXPIRY = Duration.ofMinutes(10);
    private static final int MAX_ATTEMPTS = 5;

    private final MediaRepository media;
    private final StorageService storage;
    private final UuidV7Generator ids;
    private final JdbcTemplate jdbc;
    private final long maxImageBytes;
    private final long maxVideoBytes;
    private final String publicBaseUrl;

    public MediaService(MediaRepository media, StorageService storage, UuidV7Generator ids, JdbcTemplate jdbc,
                         @Value("${app.media.max-image-bytes}") long maxImageBytes,
                         @Value("${app.media.max-video-bytes}") long maxVideoBytes,
                         @Value("${app.media.public-base-url}") String publicBaseUrl) {
        this.media = media;
        this.storage = storage;
        this.ids = ids;
        this.jdbc = jdbc;
        this.maxImageBytes = maxImageBytes;
        this.maxVideoBytes = maxVideoBytes;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Transactional
    public UploadUrlResponse requestUploadUrl(UUID ownerId, String filename, String contentType, long byteSize) {
        String mediaType;
        long maxBytes;
        if (IMAGE_CONTENT_TYPES.contains(contentType)) {
            mediaType = "image";
            maxBytes = maxImageBytes;
        } else if (VIDEO_CONTENT_TYPES.contains(contentType) || GIF_CONTENT_TYPE.equals(contentType)) {
            mediaType = "video";
            maxBytes = maxVideoBytes;
        } else {
            throw new BadRequestException("unsupported content type: " + contentType);
        }
        if (byteSize > maxBytes) {
            throw new BadRequestException("byteSize exceeds the max allowed for " + mediaType);
        }
        UUID id = ids.nextId();
        // Per-owner path namespacing is defense in depth on top of the ownership check in
        // requireOwnedAndUsable/completeUpload below.
        String key = "u/" + ownerId + "/" + id + "-" + sanitizeFilename(filename);
        String uploadUrl = storage.presignPut(key, contentType, UPLOAD_URL_EXPIRY);

        Media m = new Media();
        m.setId(id);
        m.setOwnerId(ownerId);
        m.setMediaType(mediaType);
        m.setR2Key(key);
        m.setContentType(contentType);
        m.setByteSize(byteSize);
        m.setProcessingStatus("pending");
        media.save(m);

        return new UploadUrlResponse(id, uploadUrl);
    }

    // A single headObject serves both "does it exist" and "how big is it" — defends against a client
    // claiming "done" without ever uploading, AND against a client PUTting far more than the byteSize it
    // originally declared (requestUploadUrl's cap otherwise only ever checks a number the client asserts,
    // never what was actually written to storage).
    @Transactional
    public void completeUpload(UUID mediaId, UUID ownerId) {
        Media m = requireOwner(mediaId, ownerId);
        long actualBytes = storage.headObjectContentLength(m.getR2Key())
                .orElseThrow(() -> new BadRequestException("upload not found in storage"));
        long maxBytes = "video".equals(m.getMediaType()) ? maxVideoBytes : maxImageBytes;
        if (actualBytes > maxBytes) {
            throw new BadRequestException("uploaded object exceeds the max allowed size");
        }
        m.setProcessingStatus("uploaded");
        media.save(m);
    }

    // Never trust a client-supplied mediaId without checking it resolves to something real, owned by the
    // caller, and actually usable — the same principle already applied to vote targets and moderation
    // action targets elsewhere in this codebase. expectedKind additionally ensures a video's mediaId can't
    // be attached to an "image" post (or vice versa) — mediaType is never anything but "image"/"video", so
    // this also naturally rejects any mediaId on a "text"/"link" post, closing that gap the same way.
    // Returns the validated Media so callers (PostService) don't have to re-fetch it a moment later.
    public Media requireOwnedAndUsable(UUID mediaId, UUID authorId, String expectedKind) {
        if (mediaId == null) {
            throw new BadRequestException("mediaId is required");
        }
        return requireOwnedAndUsableBatch(List.of(mediaId), authorId, expectedKind).get(0);
    }

    // Batched counterpart of requireOwnedAndUsable, for a gallery post's ordered list of images — one
    // findAllById instead of N findById round trips, same four checks per item (exists, owned,
    // ready/uploaded, type matches), same NotFoundException/ForbiddenException/BadRequestException shape.
    // Returns results in the caller's own mediaIds order (not whatever order findAllById happens to
    // return), since that order is the gallery's display order and must round-trip exactly.
    public List<Media> requireOwnedAndUsableBatch(List<UUID> mediaIds, UUID authorId, String expectedKind) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            throw new BadRequestException("mediaIds is required");
        }
        if (new HashSet<>(mediaIds).size() != mediaIds.size()) {
            throw new BadRequestException("duplicate mediaId");
        }
        Map<UUID, Media> byId = new HashMap<>();
        media.findAllById(mediaIds).forEach(m -> byId.put(m.getId(), m));
        List<Media> ordered = new ArrayList<>();
        for (UUID id : mediaIds) {
            Media m = byId.get(id);
            if (m == null) {
                throw new NotFoundException("media not found");
            }
            if (!m.getOwnerId().equals(authorId)) {
                throw new ForbiddenException("not the owner of this media");
            }
            if (!"uploaded".equals(m.getProcessingStatus()) && !"ready".equals(m.getProcessingStatus())) {
                throw new BadRequestException("media is not ready to attach to a post");
            }
            if (!m.getMediaType().equals(expectedKind)) {
                throw new BadRequestException("media type does not match post kind");
            }
            ordered.add(m);
        }
        return ordered;
    }

    public MediaView toMediaView(Media m) {
        return toView(m);
    }

    private Media requireOwner(UUID mediaId, UUID ownerId) {
        Media m = media.findById(mediaId).orElseThrow(() -> new NotFoundException("media not found"));
        if (!m.getOwnerId().equals(ownerId)) {
            throw new ForbiddenException("not the owner of this media");
        }
        return m;
    }

    // Single batched lookup, never one query per post — called from PostService for every page of posts
    // returned, plus once after PostService.create() saves a new image/video post.
    public Map<UUID, MediaView> getMediaViews(Set<UUID> mediaIds) {
        if (mediaIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, MediaView> views = new HashMap<>();
        media.findAllById(mediaIds).forEach(m -> views.put(m.getId(), toView(m)));
        return views;
    }

    private MediaView toView(Media m) {
        String thumbnailUrl = m.getThumbnailKey() == null ? null : publicBaseUrl + "/" + m.getThumbnailKey();
        String displayUrl = m.getDisplayKey() == null ? null : publicBaseUrl + "/" + m.getDisplayKey();
        return new MediaView(thumbnailUrl, displayUrl, m.getWidth(), m.getHeight(), m.getDurationSeconds(), m.getProcessingStatus());
    }

    private String sanitizeFilename(String filename) {
        String base = filename == null ? "upload" : filename;
        return base.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ==================== Called by ImageProcessingWorker / VideoProcessingWorker ====================
    // These are genuinely separate @Transactional methods on a different bean than the @Scheduled worker
    // itself, specifically so the worker's cross-bean calls go through this bean's Spring proxy — calling
    // them via self-invocation from inside the worker's own class would silently skip @Transactional,
    // the same pitfall CommunityService.create()'s own comment already flags elsewhere in this codebase.
    // The slow I/O (download, ffmpeg, upload) the worker does between claim and result-write deliberately
    // holds no open transaction/connection — unlike OutboxWorker's single-@Transactional-tick shape,
    // whose work *is* the SQL.

    // Atomic claim + flip in one statement (UPDATE ... RETURNING), same FOR UPDATE SKIP LOCKED idiom as
    // OutboxWorker's claim query, scoped to one media_type per call since image/video have very different
    // processing costs and cadences.
    @Transactional
    public List<ClaimedMedia> claimUploadedBatch(String mediaType, int batchSize) {
        return jdbc.query("""
                UPDATE media SET processing_status = 'processing', processing_started_at = now()
                WHERE id IN (
                    SELECT id FROM media
                    WHERE processing_status = 'uploaded' AND media_type = ?
                    ORDER BY created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, owner_id, media_type, r2_key, content_type, byte_size, attempt_count
                """, (rs, rowNum) -> new ClaimedMedia(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("owner_id"),
                        rs.getString("media_type"),
                        rs.getString("r2_key"),
                        rs.getString("content_type"),
                        rs.getLong("byte_size"),
                        rs.getInt("attempt_count")),
                mediaType, batchSize);
    }

    @Transactional
    public void markReady(UUID mediaId, String thumbnailKey, String displayKey,
                           Integer width, Integer height, BigDecimal durationSeconds) {
        Media m = media.findById(mediaId).orElseThrow(() -> new NotFoundException("media not found"));
        m.setThumbnailKey(thumbnailKey);
        m.setDisplayKey(displayKey);
        m.setWidth(width);
        m.setHeight(height);
        m.setDurationSeconds(durationSeconds);
        m.setProcessingStatus("ready");
        media.save(m);
    }

    // Bounded retry, terminal failed state + a logged error_message — one bad upload must not wedge the
    // queue, and a terminal failure must be diagnosable, not silent. Same lesson as the Phase 2 review's
    // outbox poison-pill finding, applied here from the start rather than discovered later.
    @Transactional
    public void markFailedOrRetry(UUID mediaId, String errorMessage) {
        Media m = media.findById(mediaId).orElseThrow(() -> new NotFoundException("media not found"));
        int attempts = m.getAttemptCount() + 1;
        m.setAttemptCount(attempts);
        m.setErrorMessage(errorMessage);
        m.setProcessingStatus(attempts >= MAX_ATTEMPTS ? "failed" : "uploaded");
        media.save(m);
    }

    // Called by MediaReaperJob. A row can be left at processing_status='processing' forever if the app
    // crashes or restarts mid-job — claimUploadedBatch's WHERE processing_status='uploaded' filter never
    // re-selects it, and markReady/markFailedOrRetry (the only other exits from 'processing') never run
    // because the worker thread that would have called them is gone. Bounded the same way
    // markFailedOrRetry already is: a row that keeps getting claimed and crashing eventually reaches the
    // terminal 'failed' state instead of being reaped forever.
    @Transactional
    public int reapStaleProcessing(int staleAfterMinutes) {
        int failed = jdbc.update("""
                UPDATE media SET processing_status = 'failed', processing_started_at = NULL,
                                  error_message = 'stuck in processing across a restart, exceeded retry attempts'
                WHERE processing_status = 'processing' AND processing_started_at < now() - make_interval(mins => ?)
                  AND attempt_count + 1 >= ?
                """, staleAfterMinutes, MAX_ATTEMPTS);
        int retried = jdbc.update("""
                UPDATE media SET processing_status = 'uploaded', processing_started_at = NULL,
                                  attempt_count = attempt_count + 1
                WHERE processing_status = 'processing' AND processing_started_at < now() - make_interval(mins => ?)
                  AND attempt_count + 1 < ?
                """, staleAfterMinutes, MAX_ATTEMPTS);
        return failed + retried;
    }
}
