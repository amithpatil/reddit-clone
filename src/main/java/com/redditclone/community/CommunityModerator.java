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

    // Bitmask semantics, added in Phase 3. OWNER_PERMISSIONS (every bit set) already satisfies every one
    // of these, so the moderator rows Phase 1 created (community creators, via CommunityService.create)
    // need no migration/backfill.
    public static final int PERM_REMOVE_CONTENT = 1;
    public static final int PERM_BAN_USERS = 1 << 1;
    public static final int PERM_MUTE_USERS = 1 << 2;
    public static final int PERM_MANAGE_AUTOMOD = 1 << 3;
    public static final int PERM_MANAGE_MODERATORS = 1 << 4;
    public static final int PERM_MANAGE_FLAIRS = 1 << 5;
    public static final int PERM_MANAGE_POSTS = 1 << 6;
    public static final int PERM_MANAGE_RULES = 1 << 7;
    public static final int PERM_MANAGE_ACCESS = 1 << 8;
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
