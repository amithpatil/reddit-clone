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

    // The NOT EXISTS clause against HiddenItem is a standard JPQL subquery against another mapped
    // entity — Hibernate resolves "HiddenItem" against its global metamodel regardless of which Java
    // package declares it, and since the reference lives inside this @Query string (not a Java import),
    // it creates no compile-time post -> engagement dependency and no ModuleBoundaryTest cycle risk.
    // viewerId is null for an unauthenticated request (these endpoints stay public), in which case the
    // filter is skipped entirely rather than matching nothing.
    @Query("""
            SELECT p FROM Post p
            WHERE p.communityId = :communityId AND p.removed = false
              AND (p.createdAt < :cursorCreatedAt OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<Post> findNewPage(@Param("communityId") UUID communityId,
                            @Param("cursorCreatedAt") Instant cursorCreatedAt,
                            @Param("cursorId") UUID cursorId,
                            @Param("viewerId") UUID viewerId,
                            Pageable limit);

    // Sitewide "r/all" counterpart of findNewPage above — identical keyset ordering and hidden-items
    // filter, just without the single-community predicate. See PostController/PostService for how the
    // "all" pseudo-community name routes here instead of the per-community method.
    @Query("""
            SELECT p FROM Post p
            WHERE p.removed = false
              AND (p.createdAt < :cursorCreatedAt OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<Post> findNewAllPage(@Param("cursorCreatedAt") Instant cursorCreatedAt,
                               @Param("cursorId") UUID cursorId,
                               @Param("viewerId") UUID viewerId,
                               Pageable limit);

    @Modifying
    @Query("UPDATE Post p SET p.commentCount = p.commentCount + 1 WHERE p.id = :id")
    void incrementCommentCount(@Param("id") UUID id);

    // A user's own "submitted" tab (F7) — identical shape to findNewAllPage (same removed/HiddenItem
    // filters, same (createdAt, id) keyset order), scoped by author instead of sitewide.
    @Query("""
            SELECT p FROM Post p
            WHERE p.authorId = :authorId AND p.removed = false
              AND (p.createdAt < :cursorCreatedAt OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<Post> findByAuthorId(@Param("authorId") UUID authorId,
                               @Param("cursorCreatedAt") Instant cursorCreatedAt,
                               @Param("cursorId") UUID cursorId,
                               @Param("viewerId") UUID viewerId,
                               Pageable limit);

    @Query("""
            SELECT p FROM Post p
            WHERE p.communityId = :communityId AND p.removed = false
              AND (p.hotRank < :cursorRank OR (p.hotRank = :cursorRank AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
            ORDER BY p.hotRank DESC, p.id DESC
            """)
    List<Post> findHotPage(@Param("communityId") UUID communityId,
                            @Param("cursorRank") double cursorRank,
                            @Param("cursorId") UUID cursorId,
                            @Param("viewerId") UUID viewerId,
                            Pageable limit);

    @Query("""
            SELECT p FROM Post p
            WHERE p.removed = false
              AND (p.hotRank < :cursorRank OR (p.hotRank = :cursorRank AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
            ORDER BY p.hotRank DESC, p.id DESC
            """)
    List<Post> findHotAllPage(@Param("cursorRank") double cursorRank,
                               @Param("cursorId") UUID cursorId,
                               @Param("viewerId") UUID viewerId,
                               Pageable limit);

    @Query("""
            SELECT p FROM Post p
            WHERE p.communityId = :communityId AND p.removed = false AND p.createdAt >= :since
              AND (p.score < :cursorScore OR (p.score = :cursorScore AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
            ORDER BY p.score DESC, p.id DESC
            """)
    List<Post> findTopPage(@Param("communityId") UUID communityId,
                            @Param("since") Instant since,
                            @Param("cursorScore") int cursorScore,
                            @Param("cursorId") UUID cursorId,
                            @Param("viewerId") UUID viewerId,
                            Pageable limit);

    @Query("""
            SELECT p FROM Post p
            WHERE p.removed = false AND p.createdAt >= :since
              AND (p.score < :cursorScore OR (p.score = :cursorScore AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
            ORDER BY p.score DESC, p.id DESC
            """)
    List<Post> findTopAllPage(@Param("since") Instant since,
                               @Param("cursorScore") int cursorScore,
                               @Param("cursorId") UUID cursorId,
                               @Param("viewerId") UUID viewerId,
                               Pageable limit);

    @Query("""
            SELECT p FROM Post p
            WHERE p.communityId = :communityId AND p.removed = false
              AND (p.risingRank < :cursorRank OR (p.risingRank = :cursorRank AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
            ORDER BY p.risingRank DESC, p.id DESC
            """)
    List<Post> findRisingPage(@Param("communityId") UUID communityId,
                               @Param("cursorRank") double cursorRank,
                               @Param("cursorId") UUID cursorId,
                               @Param("viewerId") UUID viewerId,
                               Pageable limit);

    @Query("""
            SELECT p FROM Post p
            WHERE p.removed = false
              AND (p.risingRank < :cursorRank OR (p.risingRank = :cursorRank AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
            ORDER BY p.risingRank DESC, p.id DESC
            """)
    List<Post> findRisingAllPage(@Param("cursorRank") double cursorRank,
                                  @Param("cursorId") UUID cursorId,
                                  @Param("viewerId") UUID viewerId,
                                  Pageable limit);

    @Query("""
            SELECT p FROM Post p
            WHERE p.communityId = :communityId AND p.removed = false
              AND (p.controversialRank < :cursorRank OR (p.controversialRank = :cursorRank AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
            ORDER BY p.controversialRank DESC, p.id DESC
            """)
    List<Post> findControversialPage(@Param("communityId") UUID communityId,
                                      @Param("cursorRank") double cursorRank,
                                      @Param("cursorId") UUID cursorId,
                                      @Param("viewerId") UUID viewerId,
                                      Pageable limit);

    @Query("""
            SELECT p FROM Post p
            WHERE p.removed = false
              AND (p.controversialRank < :cursorRank OR (p.controversialRank = :cursorRank AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
            ORDER BY p.controversialRank DESC, p.id DESC
            """)
    List<Post> findControversialAllPage(@Param("cursorRank") double cursorRank,
                                         @Param("cursorId") UUID cursorId,
                                         @Param("viewerId") UUID viewerId,
                                         Pageable limit);

    // Returns ranked ids only, not full entities: search_vector is deliberately unmapped on Post (see its
    // comment), so a native query selecting p.* would return an extra column Hibernate isn't expecting.
    // PostService.search fetches the actual entities via the ordinary, safely-mapped findAllById and
    // re-applies this ranking order, rather than fighting native-query-to-entity mapping for one endpoint.
    @Query(value = """
            SELECT id FROM posts
            WHERE community_id = :communityId AND NOT removed
              AND search_vector @@ websearch_to_tsquery('english', :query)
            ORDER BY ts_rank(search_vector, websearch_to_tsquery('english', :query)) DESC
            LIMIT 25
            """, nativeQuery = true)
    List<UUID> searchIds(@Param("communityId") UUID communityId, @Param("query") String query);

    int countByCommunityIdAndPinnedTrue(UUID communityId);

    List<Post> findByCommunityIdAndPinnedTrueAndRemovedFalseOrderByCreatedAtDesc(UUID communityId);
}
