package com.redditclone.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CommunityModeratorRepository extends JpaRepository<CommunityModerator, CommunityModeratorId> {

    Optional<CommunityModerator> findByCommunityIdAndUserId(UUID communityId, UUID userId);

    boolean existsByCommunityIdAndUserId(UUID communityId, UUID userId);

    long deleteByCommunityIdAndUserId(UUID communityId, UUID userId);
}
