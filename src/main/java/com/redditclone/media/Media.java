package com.redditclone.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "media")
public class Media {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "media_type", nullable = false)
    private String mediaType; // image | video

    @Column(name = "r2_key", nullable = false, unique = true)
    private String r2Key;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "thumbnail_key")
    private String thumbnailKey;

    @Column(name = "display_key")
    private String displayKey;

    private Integer width;

    private Integer height;

    @Column(name = "duration_seconds")
    private BigDecimal durationSeconds;

    @Column(name = "processing_status", nullable = false)
    private String processingStatus = "pending"; // pending | uploaded | processing | ready | failed

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // Captured from MDC at upload-request time (see CorrelationIdFilter / MediaService.requestUploadUrl) —
    // persisted so ImageProcessingWorker/VideoProcessingWorker can re-apply it on their own, later thread
    // when this row is eventually processed, long after the original request's own MDC is gone.
    @Column(name = "correlation_id")
    private String correlationId;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    public String getR2Key() {
        return r2Key;
    }

    public void setR2Key(String r2Key) {
        this.r2Key = r2Key;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getByteSize() {
        return byteSize;
    }

    public void setByteSize(long byteSize) {
        this.byteSize = byteSize;
    }

    public String getThumbnailKey() {
        return thumbnailKey;
    }

    public void setThumbnailKey(String thumbnailKey) {
        this.thumbnailKey = thumbnailKey;
    }

    public String getDisplayKey() {
        return displayKey;
    }

    public void setDisplayKey(String displayKey) {
        this.displayKey = displayKey;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public BigDecimal getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(BigDecimal durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
}
