package com.redditclone.comment.dto;

import com.redditclone.common.paging.Listing;
import com.redditclone.post.Post;

public record PostWithCommentsView(Post post, Listing<CommentView> comments) {
}
