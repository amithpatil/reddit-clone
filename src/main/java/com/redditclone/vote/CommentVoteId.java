package com.redditclone.vote;

import java.io.Serializable;
import java.util.UUID;

public record CommentVoteId(UUID userId, UUID commentId) implements Serializable {
}
