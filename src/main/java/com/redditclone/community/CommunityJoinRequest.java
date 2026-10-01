package com.redditclone.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.UUID;

// One mechanism, status-driven: a fresh request (or a re-request after a prior denial, or after leaving
// and coming back) always resets cleanly to "pending" rather than special-casing prior states — see
// CommunityService.requestToJoin. Approval creates a real Membership row; this table is only the workflow,
// never consulted at view-access-check time (requireViewAccess just checks Membership/moderator).
@Entity
@Table(name = "community_join_requests")
@IdClass(CommunityJoinRequestId.class)
public class CommunityJoinRequest {

    @Id
    @Column(name = "community_id")
    private UUID communityId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private String status; // pending | approved | denied

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    // Populated by CommunityService.listJoinRequests (F8) — same display-attach convention as
    // Post.authorUsername, batched via AuthService.findUsernamesByIds.
    @Transient
    private String username;

    public CommunityJoinRequest() {
    }

    public CommunityJoinRequest(UUID communityId, UUID userId, String status) {
        this.communityId = communityId;
        this.userId = userId;
        this.status = status;
    }

    public UUID getCommunityId() {
        return communityId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(UUID decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}
