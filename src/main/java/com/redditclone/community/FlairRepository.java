package com.redditclone.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FlairRepository extends JpaRepository<Flair, UUID> {

    List<Flair> findByCommunityId(UUID communityId);

    List<Flair> findByCommunityIdAndType(UUID communityId, String type);
}
