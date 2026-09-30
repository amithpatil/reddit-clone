package com.redditclone.vote;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "comment_votes")
@IdClass(CommentVoteId.class)
public class CommentVote {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "comment_id")
    private UUID commentId;

    @Column(nullable = false)
    private short direction; // +1 or -1, never 0 — see Data model, Resolved design decisions

    @Column(name = "voted_at", nullable = false)
    private Instant votedAt = Instant.now();

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getCommentId() {
        return commentId;
    }

    public void setCommentId(UUID commentId) {
        this.commentId = commentId;
    }

    public short getDirection() {
        return direction;
    }

    public void setDirection(short direction) {
        this.direction = direction;
    }

    public Instant getVotedAt() {
        return votedAt;
    }

    public void setVotedAt(Instant votedAt) {
        this.votedAt = votedAt;
    }
}
