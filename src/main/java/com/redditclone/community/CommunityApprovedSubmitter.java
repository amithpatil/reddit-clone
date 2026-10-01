package com.redditclone.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// Restricted-type-only concept: presence grants posting rights, nothing else — no pending/request state
// at all, a mod grants it directly. Deliberately not the same table as CommunityJoinRequest (private's
// workflow), which needs a status column this concept has no use for.
@Entity
@Table(name = "community_approved_submitters")
@IdClass(CommunityApprovedSubmitterId.class)
public class CommunityApprovedSubmitter {

    @Id
    @Column(name = "community_id")
    private UUID communityId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt = Instant.now();

    public CommunityApprovedSubmitter() {
    }

    public CommunityApprovedSubmitter(UUID communityId, UUID userId, UUID approvedBy) {
        this.communityId = communityId;
        this.userId = userId;
        this.approvedBy = approvedBy;
    }

    public UUID getCommunityId() {
        return communityId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }
}
