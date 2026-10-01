package com.redditclone.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.UUID;

// Populated by ReportService.fileReport (upsert on every new report — see its comment) and by
// community.CommunityService.evaluateAutomod (an automod "report" verdict). Both write via raw SQL
// against this shared table; this entity is the read side, used by ModerationController's queue view.
@Entity
@Table(name = "mod_queue")
@IdClass(ModQueueEntryId.class)
public class ModQueueEntry {

    @Id
    @Column(name = "community_id")
    private UUID communityId;

    @Id
    @Column(name = "target_type")
    private String targetType;

    @Id
    @Column(name = "target_id")
    private UUID targetId;

    @Column(name = "report_count", nullable = false)
    private int reportCount;

    @Column(name = "first_reported_at", nullable = false)
    private Instant firstReportedAt;

    // Populated by ModerationService.listModQueue (F8) via a batched Post/Comment lookup split by
    // targetType — a bare targetId tells a moderator nothing about what was actually reported. preview is
    // the post's title, or a comment's body truncated to a fixed length; null if the target has since been
    // deleted/removed out from under the queue entry.
    @Transient
    private String preview;

    @Transient
    private String authorUsername;

    public UUID getCommunityId() {
        return communityId;
    }

    public String getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public int getReportCount() {
        return reportCount;
    }

    public Instant getFirstReportedAt() {
        return firstReportedAt;
    }

    public String getPreview() {
        return preview;
    }

    public void setPreview(String preview) {
        this.preview = preview;
    }

    public String getAuthorUsername() {
        return authorUsername;
    }

    public void setAuthorUsername(String authorUsername) {
        this.authorUsername = authorUsername;
    }
}
