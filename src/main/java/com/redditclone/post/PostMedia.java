package com.redditclone.post;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.util.UUID;

// One row per image in a "gallery"-kind post's ordered image list — every other post kind has at most one
// media item (Post.mediaId), so this table only ever has rows for gallery posts. position is the user's
// chosen order at submission time; posts are never edited after creation (no post-edit endpoint exists
// anywhere in this app), so position is write-once, same as every other post field.
@Entity
@Table(name = "post_media")
@IdClass(PostMediaId.class)
public class PostMedia {

    @Id
    @Column(name = "post_id")
    private UUID postId;

    @Id
    @Column(name = "media_id")
    private UUID mediaId;

    @Column(name = "position", nullable = false)
    private short position;

    public PostMedia() {
    }

    public PostMedia(UUID postId, UUID mediaId, short position) {
        this.postId = postId;
        this.mediaId = mediaId;
        this.position = position;
    }

    public UUID getPostId() {
        return postId;
    }

    public void setPostId(UUID postId) {
        this.postId = postId;
    }

    public UUID getMediaId() {
        return mediaId;
    }

    public void setMediaId(UUID mediaId) {
        this.mediaId = mediaId;
    }

    public short getPosition() {
        return position;
    }

    public void setPosition(short position) {
        this.position = position;
    }
}
