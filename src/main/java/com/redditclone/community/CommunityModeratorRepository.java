package com.redditclone.community;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityModeratorRepository extends JpaRepository<CommunityModerator, CommunityModeratorId> {
}
