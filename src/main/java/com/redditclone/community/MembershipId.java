package com.redditclone.community;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class MembershipId implements Serializable {

    private UUID userId;
    private UUID communityId;

    public MembershipId() {
    }

    public MembershipId(UUID userId, UUID communityId) {
        this.userId = userId;
        this.communityId = communityId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MembershipId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(communityId, that.communityId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, communityId);
    }
}
