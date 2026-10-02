package com.redditclone.post;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class PostMediaId implements Serializable {

    private UUID postId;
    private UUID mediaId;

    public PostMediaId() {
    }

    public PostMediaId(UUID postId, UUID mediaId) {
        this.postId = postId;
        this.mediaId = mediaId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PostMediaId that)) return false;
        return Objects.equals(postId, that.postId) && Objects.equals(mediaId, that.mediaId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(postId, mediaId);
    }
}
