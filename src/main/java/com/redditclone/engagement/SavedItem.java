package com.redditclone.engagement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saved_items")
@IdClass(SavedItemId.class)
public class SavedItem {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "target_type")
    private String targetType; // post | comment

    @Id
    @Column(name = "target_id")
    private UUID targetId;

    @Column(name = "saved_at", nullable = false)
    private Instant savedAt = Instant.now();

    public SavedItem() {
    }

    public SavedItem(UUID userId, String targetType, UUID targetId) {
        this.userId = userId;
        this.targetType = targetType;
        this.targetId = targetId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public Instant getSavedAt() {
        return savedAt;
    }
}
