package com.redditclone.comment.dto;

import java.time.Instant;
import java.util.UUID;

// A flat row for a user's own "comments" profile tab (F7) — deliberately NOT a reuse of CommentView, whose
// nested-tree contract (replies, path, depth, "[removed]" tombstoning so reply subtrees aren't orphaned)
// exists to solve a problem specific to a single post's comment thread. This listing has no tree to orphan
// — CommentRepository.findByAuthorId simply excludes removed comments, same as every other listing query
// in this codebase (posts, feeds), rather than tombstoning them.
public record UserCommentView(UUID id, UUID postId, String postTitle, String communityName, UUID parentId,
                               String body, int score, Instant createdAt) {
}
