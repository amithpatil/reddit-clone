package com.redditclone.moderation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ModMailMessageRepository extends JpaRepository<ModMailMessage, UUID> {

    List<ModMailMessage> findByCommunityIdOrderByCreatedAtDesc(UUID communityId, Pageable pageable);
}
