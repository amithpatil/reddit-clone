package com.redditclone.engagement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface HiddenItemRepository extends JpaRepository<HiddenItem, HiddenItemId> {

    boolean existsByUserIdAndTargetTypeAndTargetId(UUID userId, String targetType, UUID targetId);

    void deleteByUserIdAndTargetTypeAndTargetId(UUID userId, String targetType, UUID targetId);
}
