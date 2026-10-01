package com.redditclone.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, MembershipId> {

    boolean existsByUserIdAndCommunityId(UUID userId, UUID communityId);

    long deleteByUserIdAndCommunityId(UUID userId, UUID communityId);

    // Batched counterpart of existsByUserIdAndCommunityId — read by
    // CommunityService.attachViewerContextBatch so a page of many communities costs one query, not one per
    // community.
    List<Membership> findByUserIdAndCommunityIdIn(UUID userId, Collection<UUID> communityIds);
}
