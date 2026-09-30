package com.redditclone.post;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "posts")
public class Post {

    @Id
    private UUID id;

    @Column(name = "community_id", nullable = false)
    private UUID communityId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(nullable = false)
    private String kind; // text | link | image | video

    @Column(nullable = false)
    private String title;

    private String body;

    private String url;

    @Column(nullable = false)
    private boolean nsfw;

    @Column(nullable = false)
    private boolean spoiler;

    @Column(nullable = false)
    private int score = 0;

    @Column(name = "comment_count", nullable = false)
    private int commentCount = 0;

    @Column(name = "hot_rank", nullable = false)
    private double hotRank = 0;

    @Column(nullable = false)
    private int ups = 0;

    @Column(nullable = false)
    private int downs = 0;

    @Column(name = "controversial_rank", nullable = false)
    private double controversialRank = 0;

    @Column(name = "rising_rank", nullable = false)
    private double risingRank = 0;

    @Column(name = "rising_updated_at", nullable = false)
    private Instant risingUpdatedAt = Instant.now();

    @Column(nullable = false)
    private boolean pinned;

    @Column(nullable = false)
    private boolean locked;

    @Column(nullable = false)
    private boolean removed;

    // search_vector is intentionally not mapped: it's populated by the V3 posts_search_vector_trigger
    // and unused until Phase 3 search. ddl-auto=validate only checks mapped columns, so leaving it out is safe.

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

    public UUID getAuthorId() {
        return authorId;
    }

    public void setAuthorId(UUID authorId) {
        this.authorId = authorId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isNsfw() {
        return nsfw;
    }

    public void setNsfw(boolean nsfw) {
        this.nsfw = nsfw;
    }

    public boolean isSpoiler() {
        return spoiler;
    }

    public void setSpoiler(boolean spoiler) {
        this.spoiler = spoiler;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public int getCommentCount() {
        return commentCount;
    }

    // No setter: commentCount is exclusively mutated via PostRepository.incrementCommentCount's atomic
    // bulk UPDATE, same reasoning as Comment.childCount.

    public double getHotRank() {
        return hotRank;
    }

    // No setter: hot_rank/ups/downs/controversial_rank/rising_rank/rising_updated_at are exclusively
    // mutated via PostService.applyVoteDeltas's raw-SQL bulk update, same reasoning as commentCount —
    // Hibernate still hydrates these fields on read via its own field access, no setter needed for that.

    public int getUps() {
        return ups;
    }

    public int getDowns() {
        return downs;
    }

    public double getControversialRank() {
        return controversialRank;
    }

    public double getRisingRank() {
        return risingRank;
    }

    public Instant getRisingUpdatedAt() {
        return risingUpdatedAt;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public boolean isRemoved() {
        return removed;
    }

    public void setRemoved(boolean removed) {
        this.removed = removed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
