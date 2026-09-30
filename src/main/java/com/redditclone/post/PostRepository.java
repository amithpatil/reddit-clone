package com.redditclone.post;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PostRepository extends JpaRepository<Post, UUID> {

    @Query("""
            SELECT p FROM Post p
            WHERE p.communityId = :communityId AND p.removed = false
              AND (p.createdAt < :cursorCreatedAt OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<Post> findNewPage(@Param("communityId") UUID communityId,
                            @Param("cursorCreatedAt") Instant cursorCreatedAt,
                            @Param("cursorId") UUID cursorId,
                            Pageable limit);

    @Modifying
    @Query("UPDATE Post p SET p.commentCount = p.commentCount + 1 WHERE p.id = :id")
    void incrementCommentCount(@Param("id") UUID id);

    @Query("""
            SELECT p FROM Post p
            WHERE p.communityId = :communityId AND p.removed = false
              AND (p.hotRank < :cursorRank OR (p.hotRank = :cursorRank AND p.id < :cursorId))
            ORDER BY p.hotRank DESC, p.id DESC
            """)
    List<Post> findHotPage(@Param("communityId") UUID communityId,
                            @Param("cursorRank") double cursorRank,
                            @Param("cursorId") UUID cursorId,
                            Pageable limit);

    @Query("""
            SELECT p FROM Post p
            WHERE p.communityId = :communityId AND p.removed = false AND p.createdAt >= :since
              AND (p.score < :cursorScore OR (p.score = :cursorScore AND p.id < :cursorId))
            ORDER BY p.score DESC, p.id DESC
            """)
    List<Post> findTopPage(@Param("communityId") UUID communityId,
                            @Param("since") Instant since,
                            @Param("cursorScore") int cursorScore,
                            @Param("cursorId") UUID cursorId,
                            Pageable limit);

    @Query("""
            SELECT p FROM Post p
            WHERE p.communityId = :communityId AND p.removed = false
              AND (p.risingRank < :cursorRank OR (p.risingRank = :cursorRank AND p.id < :cursorId))
            ORDER BY p.risingRank DESC, p.id DESC
            """)
    List<Post> findRisingPage(@Param("communityId") UUID communityId,
                               @Param("cursorRank") double cursorRank,
                               @Param("cursorId") UUID cursorId,
                               Pageable limit);

    @Query("""
            SELECT p FROM Post p
            WHERE p.communityId = :communityId AND p.removed = false
              AND (p.controversialRank < :cursorRank OR (p.controversialRank = :cursorRank AND p.id < :cursorId))
            ORDER BY p.controversialRank DESC, p.id DESC
            """)
    List<Post> findControversialPage(@Param("communityId") UUID communityId,
                                      @Param("cursorRank") double cursorRank,
                                      @Param("cursorId") UUID cursorId,
                                      Pageable limit);
}
