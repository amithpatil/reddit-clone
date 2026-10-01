package com.redditclone.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CommunityApprovedSubmitterRepository extends JpaRepository<CommunityApprovedSubmitter, CommunityApprovedSubmitterId> {

    boolean existsByCommunityIdAndUserId(UUID communityId, UUID userId);

    void deleteByCommunityIdAndUserId(UUID communityId, UUID userId);
}
