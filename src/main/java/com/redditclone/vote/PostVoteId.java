package com.redditclone.vote;

import java.io.Serializable;
import java.util.UUID;

public record PostVoteId(UUID userId, UUID postId) implements Serializable {
}
