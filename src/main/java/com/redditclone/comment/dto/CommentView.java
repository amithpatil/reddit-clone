package com.redditclone.comment.dto;

import com.redditclone.comment.Comment;

import java.time.Instant;
import java.util.UUID;

// A removed comment keeps its row (so replies underneath never lose their place) — the API just returns
// "[removed]" as the body wherever removed=true, mirroring how Reddit preserves a deleted comment's spot.
public record CommentView(UUID id, UUID postId, UUID parentId, String path, short depth, UUID authorId,
                           String body, int score, int childCount, boolean removed, Instant createdAt) {

    public static CommentView from(Comment c) {
        return new CommentView(c.getId(), c.getPostId(), c.getParentId(), c.getPath(), c.getDepth(),
                c.getAuthorId(), c.isRemoved() ? "[removed]" : c.getBody(), c.getScore(), c.getChildCount(),
                c.isRemoved(), c.getCreatedAt());
    }
}
