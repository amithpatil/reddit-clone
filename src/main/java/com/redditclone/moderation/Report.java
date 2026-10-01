package com.redditclone.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.UUID;

// reports is RANGE-partitioned by created_at (V5__moderation.sql), which requires created_at in the
// table's actual primary key — mapping only `id` here would let Hibernate's findById/merge queries omit
// the partition key, forcing Postgres to scan every partition instead of pruning to the relevant one.
@Entity
@Table(name = "reports")
@IdClass(ReportId.class)
public class Report {

    @Id
    private UUID id;

    @Column(name = "target_type", nullable = false)
    private String targetType; // post | comment

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "community_id", nullable = false)
    private UUID communityId;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private String status = "open"; // open | resolved | dismissed

    @Column(name = "resolver_id")
    private UUID resolverId;

    @Id
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // Populated by ModerationService.listReportsForTarget (F8) — same display-attach convention as
    // Post.authorUsername, batched via AuthService.findUsernamesByIds, never one lookup per report.
    @Transient
    private String reporterUsername;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public void setTargetId(UUID targetId) {
        this.targetId = targetId;
    }

    public UUID getCommunityId() {
        return communityId;
    }

    public void setCommunityId(UUID communityId) {
        this.communityId = communityId;
    }

    public UUID getReporterId() {
        return reporterId;
    }

    public void setReporterId(UUID reporterId) {
        this.reporterId = reporterId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getResolverId() {
        return resolverId;
    }

    public void setResolverId(UUID resolverId) {
        this.resolverId = resolverId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getReporterUsername() {
        return reporterUsername;
    }

    public void setReporterUsername(String reporterUsername) {
        this.reporterUsername = reporterUsername;
    }
}
