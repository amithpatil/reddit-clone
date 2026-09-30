package com.redditclone.moderation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ModQueueRepository extends JpaRepository<ModQueueEntry, ModQueueEntryId> {

    List<ModQueueEntry> findByCommunityIdOrderByFirstReportedAtAsc(UUID communityId, Pageable pageable);
}
