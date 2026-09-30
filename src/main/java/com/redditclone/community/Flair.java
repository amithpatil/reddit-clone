package com.redditclone.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// Community-scoped, mod-managed tag — structurally identical to AutomodRule (same community_id +
// mod-CRUD shape), but with no internal/sensitive fields, so unlike Media/MediaView this entity is
// returned and embedded directly everywhere rather than through a dedicated view DTO.
@Entity
@Table(name = "flairs")
public class Flair {

    @Id
    private UUID id;

    @Column(name = "community_id", nullable = false)
    private UUID communityId;

    @Column(nullable = false)
    private String text;

    @Column(nullable = false)
    private String color;

    @Column(nullable = false)
    private String type; // user | post

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getCommunityId() {
        return communityId;
    }

    public void setCommunityId(UUID communityId) {
        this.communityId = communityId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
