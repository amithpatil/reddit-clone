package com.redditclone.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.UUID;

// One mechanism, not two: expiresAt null = permanent ban, expiresAt set = temporary (auto-expires, no
// explicit unban needed — see CommunityService.requireNotBanned). Blocks posting and commenting only,
// not voting or browsing — matches Reddit's own community-ban behavior, confirmed with the user.
@Entity
@Table(name = "bans")
@IdClass(BanId.class)
public class Ban {

    @Id
    @Column(name = "community_id")
    private UUID communityId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "issuer_id", nullable = false)
    private UUID issuerId;

    private String reason;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // Populated by CommunityService.listBans (F8) — same display-attach convention as Post.authorUsername,
    // batched via AuthService.findUsernamesByIds, never one lookup per ban.
    @Transient
    private String username;

    @Transient
    private String issuerUsername;

    public Ban() {
    }

    public Ban(UUID communityId, UUID userId, UUID issuerId, String reason, Instant expiresAt) {
        this.communityId = communityId;
        this.userId = userId;
        this.issuerId = issuerId;
        this.reason = reason;
        this.expiresAt = expiresAt;
    }

    public UUID getCommunityId() {
        return communityId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getIssuerId() {
        return issuerId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    // Lets issueBan() preserve the original ban's timestamp when re-issuing over an existing row (JPA
    // merge would otherwise overwrite created_at with the new instance's Instant.now() default).
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getIssuerUsername() {
        return issuerUsername;
    }

    public void setIssuerUsername(String issuerUsername) {
        this.issuerUsername = issuerUsername;
    }
}
