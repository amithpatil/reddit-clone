package com.redditclone.comment.dto;

import com.redditclone.post.Post;

import java.util.List;

public record PostWithCommentsView(Post post, List<CommentView> comments) {
}
