package com.redditclone.community;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class CommunityJoinRequestId implements Serializable {

    private UUID communityId;
    private UUID userId;

    public CommunityJoinRequestId() {
    }

    public CommunityJoinRequestId(UUID communityId, UUID userId) {
        this.communityId = communityId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CommunityJoinRequestId that)) return false;
        return Objects.equals(communityId, that.communityId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(communityId, userId);
    }
}
