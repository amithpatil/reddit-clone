package com.redditclone.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CommunityJoinRequestRepository extends JpaRepository<CommunityJoinRequest, CommunityJoinRequestId> {

    List<CommunityJoinRequest> findByCommunityIdAndStatus(UUID communityId, String status);
}
