package com.redditclone.community;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "communities")
public class Community {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, columnDefinition = "citext")
    private String name;

    @Column(nullable = false)
    private String type = "public";

    private String description;

    @Column(name = "creator_id", nullable = false)
    private UUID creatorId;

    @Column(name = "subscriber_count", nullable = false)
    private int subscriberCount = 0;

    // Human-readable sidebar rules (e.g. "1. Be civil") — a plain JSON string, same ColumnTransformer
    // convention as AutomodRule.config/OutboxEvent.payload, parsed/built by CommunityService, not this
    // entity. Deliberately separate from automod_rules, which is machine-evaluated filter config.
    @ColumnTransformer(write = "?::jsonb")
    @Column(columnDefinition = "jsonb", nullable = false)
    private String rules = "[]";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(UUID creatorId) {
        this.creatorId = creatorId;
    }

    public int getSubscriberCount() {
        return subscriberCount;
    }

    public void setSubscriberCount(int subscriberCount) {
        this.subscriberCount = subscriberCount;
    }

    // Hidden from any endpoint that serializes this entity directly (POST /r, GET /r/{name}/about) — this
    // is the raw JSON-encoded string Hibernate maps the column to, and returning it as-is would double-
    // encode (a string field containing already-JSON-encoded text instead of a real array). Structured
    // access goes through CommunityService.getRules()/the dedicated GET /{name}/rules endpoint instead.
    @JsonIgnore
    public String getRules() {
        return rules;
    }

    public void setRules(String rules) {
        this.rules = rules;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
