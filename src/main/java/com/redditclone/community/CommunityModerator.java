package com.redditclone.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "community_moderators")
@IdClass(CommunityModeratorId.class)
public class CommunityModerator {

    // Bitmask semantics land with Phase 3 Moderation; for now every moderator row created in Phase 1
    // (only the community creator, via CommunityService.create) uses this "owner" placeholder value.
    public static final int OWNER_PERMISSIONS = Integer.MAX_VALUE;

    @Id
    @Column(name = "community_id")
    private UUID communityId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private int permissions;

    @Column(name = "added_by")
    private UUID addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt = Instant.now();

    public CommunityModerator() {
    }

    public CommunityModerator(UUID communityId, UUID userId, int permissions, UUID addedBy) {
        this.communityId = communityId;
        this.userId = userId;
        this.permissions = permissions;
        this.addedBy = addedBy;
    }

    public UUID getCommunityId() {
        return communityId;
    }

    public void setCommunityId(UUID communityId) {
        this.communityId = communityId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public int getPermissions() {
        return permissions;
    }

    public void setPermissions(int permissions) {
        this.permissions = permissions;
    }

    public UUID getAddedBy() {
        return addedBy;
    }

    public void setAddedBy(UUID addedBy) {
        this.addedBy = addedBy;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(Instant addedAt) {
        this.addedAt = addedAt;
    }
}
