package com.redditclone.comment.dto;

import com.redditclone.comment.Comment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// A removed comment keeps its row (so replies underneath never lose their place) — the API just returns
// "[removed]" as the body wherever removed=true, mirroring how Reddit preserves a deleted comment's spot.
// `replies` makes this a genuinely nested tree (see CommentService.findCommentTree), not a flat list the
// client has to reconstruct — each entry's replies are already sorted by the same comparator as its
// siblings, recursively, matching how Reddit itself sorts a thread at every depth, not just the root.
public record CommentView(UUID id, UUID postId, UUID parentId, String path, short depth, UUID authorId,
                           String authorUsername, String body, int score, int ups, int downs, double bestRank,
                           double controversialRank, int childCount, boolean removed, Instant createdAt,
                           List<CommentView> replies) {

    public static CommentView from(Comment c) {
        return from(c, List.of());
    }

    public static CommentView from(Comment c, List<CommentView> replies) {
        return new CommentView(c.getId(), c.getPostId(), c.getParentId(), c.getPath(), c.getDepth(),
                c.getAuthorId(), c.getAuthorUsername(), c.isRemoved() ? "[removed]" : c.getBody(), c.getScore(),
                c.getUps(), c.getDowns(), c.getBestRank(), c.getControversialRank(), c.getChildCount(),
                c.isRemoved(), c.getCreatedAt(), replies);
    }
}
