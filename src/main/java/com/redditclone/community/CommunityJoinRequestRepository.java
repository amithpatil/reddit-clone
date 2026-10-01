package com.redditclone.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CommunityJoinRequestRepository extends JpaRepository<CommunityJoinRequest, CommunityJoinRequestId> {

    List<CommunityJoinRequest> findByCommunityIdAndStatus(UUID communityId, String status);

    // Batched counterpart of the single-lookup findById(new CommunityJoinRequestId(...)) used by F4's
    // attachViewerContext — read by CommunityService.attachViewerContextBatch, same never-N+1 reasoning.
    List<CommunityJoinRequest> findByUserIdAndCommunityIdIn(UUID userId, Collection<UUID> communityIds);
}
