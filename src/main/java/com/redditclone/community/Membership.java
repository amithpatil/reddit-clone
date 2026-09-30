package com.redditclone.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "memberships")
@IdClass(MembershipId.class)
public class Membership {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "community_id")
    private UUID communityId;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "flair_id")
    private UUID flairId;

    public Membership() {
    }

    public Membership(UUID userId, UUID communityId, Instant joinedAt) {
        this.userId = userId;
        this.communityId = communityId;
        this.joinedAt = joinedAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getCommunityId() {
        return communityId;
    }

    public void setCommunityId(UUID communityId) {
        this.communityId = communityId;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }

    public UUID getFlairId() {
        return flairId;
    }

    public void setFlairId(UUID flairId) {
        this.flairId = flairId;
    }
}
