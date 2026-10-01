package com.redditclone.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

// notifications is RANGE-partitioned by created_at (V6__engagement.sql), which requires created_at in
// the table's actual primary key — mapping only `id` would let findById/merge queries omit the partition
// key and force a scan across every partition. Same fix already applied to moderation.Report for the
// identical reason.
@Entity
@Table(name = "notifications")
@IdClass(NotificationId.class)
public class Notification {

    @Id
    private UUID id;

    @Id
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String type; // reply | post_reply | mention | chat_message

    // Same plain-String + columnTransformer convention as every other JSONB column in this codebase.
    @ColumnTransformer(write = "?::jsonb")
    @Column(nullable = false, columnDefinition = "jsonb")
    private String source;

    @Column(name = "read_at")
    private Instant readAt;

    // Attached batched, server-side, by NotificationService.listForUser — same @Transient
    // attach-plus-batched-lookup pattern as ModQueueEntry.preview/authorUsername (F8). Null where the
    // type doesn't carry that field (e.g. no postTitle/communityName on a chat_message row).
    @Transient
    private String postTitle;

    @Transient
    private String communityName;

    @Transient
    private String actorUsername;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public String getPostTitle() {
        return postTitle;
    }

    public void setPostTitle(String postTitle) {
        this.postTitle = postTitle;
    }

    public String getCommunityName() {
        return communityName;
    }

    public void setCommunityName(String communityName) {
        this.communityName = communityName;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public void setActorUsername(String actorUsername) {
        this.actorUsername = actorUsername;
    }
}
