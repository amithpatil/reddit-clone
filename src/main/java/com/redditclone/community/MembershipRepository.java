package com.redditclone.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, MembershipId> {

    boolean existsByUserIdAndCommunityId(UUID userId, UUID communityId);

    long deleteByUserIdAndCommunityId(UUID userId, UUID communityId);
}
