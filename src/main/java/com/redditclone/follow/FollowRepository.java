package com.redditclone.follow;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FollowRepository extends JpaRepository<Follow, FollowId> {

    boolean existsByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    long deleteByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    // Batched counterpart of existsByFollowerIdAndFolloweeId — backs the isFollowing batch resolution for a
    // page of search/list results, same shape as MembershipRepository.findByUserIdAndCommunityIdIn.
    List<Follow> findByFollowerIdAndFolloweeIdIn(UUID followerId, Collection<UUID> followeeIds);

    // Who follows this user — same keyset-pagination shape as CommunityRepository.findNewPage.
    @Query("""
            SELECT f FROM Follow f
            WHERE f.followeeId = :followeeId
              AND (f.createdAt < :cursorCreatedAt OR (f.createdAt = :cursorCreatedAt AND f.followerId < :cursorId))
            ORDER BY f.createdAt DESC, f.followerId DESC
            """)
    List<Follow> findFollowersPage(@Param("followeeId") UUID followeeId,
                                    @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                    @Param("cursorId") UUID cursorId, Pageable limit);

    // Who this user follows — mirror of findFollowersPage, keyed the other direction.
    @Query("""
            SELECT f FROM Follow f
            WHERE f.followerId = :followerId
              AND (f.createdAt < :cursorCreatedAt OR (f.createdAt = :cursorCreatedAt AND f.followeeId < :cursorId))
            ORDER BY f.createdAt DESC, f.followeeId DESC
            """)
    List<Follow> findFollowingPage(@Param("followerId") UUID followerId,
                                    @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                    @Param("cursorId") UUID cursorId, Pageable limit);
}
