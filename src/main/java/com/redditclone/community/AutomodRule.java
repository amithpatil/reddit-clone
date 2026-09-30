package com.redditclone.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

// Deliberately separate from communities.rules (human-readable sidebar text) — this is machine-evaluated
// config. config is a small JSON blob whose shape depends on ruleType: keyword -> {"keywords":[...]},
// regex -> {"pattern":"..."}, karma_threshold -> {"minKarma":N}. Same citext/ltree-adjacent lesson as
// OutboxEvent.payload: plain String + columnDefinition, no JdbcTypeCode, with an explicit ::jsonb cast
// since jsonb (like ltree, unlike citext) has no implicit cast from an unspecified-type parameter.
@Entity
@Table(name = "automod_rules")
public class AutomodRule {

    @Id
    private UUID id;

    @Column(name = "community_id", nullable = false)
    private UUID communityId;

    @Column(name = "rule_type", nullable = false)
    private String ruleType; // keyword | regex | karma_threshold

    @ColumnTransformer(write = "?::jsonb")
    @Column(nullable = false, columnDefinition = "jsonb")
    private String config;

    @Column(nullable = false)
    private String action; // remove | report — never an automatic ban, that stays a human decision

    @Column(nullable = false)
    private boolean enabled = true;

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

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
