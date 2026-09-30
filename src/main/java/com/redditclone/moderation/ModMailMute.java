package com.redditclone.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// Distinct from community.Ban: this only blocks modmail (see ModerationService.sendModMail), never
// posting/commenting/voting. Same permanent-vs-temporary-by-expiry convention as Ban.
@Entity
@Table(name = "mod_mail_mutes")
@IdClass(ModMailMuteId.class)
public class ModMailMute {

    @Id
    @Column(name = "community_id")
    private UUID communityId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "muted_by", nullable = false)
    private UUID mutedBy;

    private String reason;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public ModMailMute() {
    }

    public ModMailMute(UUID communityId, UUID userId, UUID mutedBy, String reason, Instant expiresAt) {
        this.communityId = communityId;
        this.userId = userId;
        this.mutedBy = mutedBy;
        this.reason = reason;
        this.expiresAt = expiresAt;
    }

    public UUID getCommunityId() {
        return communityId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getMutedBy() {
        return mutedBy;
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

    // Lets muteUser() preserve the original mute's timestamp when re-issuing over an existing row (JPA
    // merge would otherwise overwrite created_at with the new instance's Instant.now() default).
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
