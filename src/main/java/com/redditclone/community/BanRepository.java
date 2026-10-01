package com.redditclone.community;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BanRepository extends JpaRepository<Ban, BanId> {

    // F8's Bans tab (first-ever consumer — until now a moderator could issue/lift a ban but never see the
    // current list at all).
    List<Ban> findByCommunityIdOrderByCreatedAtDesc(UUID communityId, Pageable limit);
}
