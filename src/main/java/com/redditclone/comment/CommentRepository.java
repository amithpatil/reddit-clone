package com.redditclone.comment;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    // Ordered by best_rank (Wilson score lower bound on the up/down split, see RankFormulas.bestRank) —
    // Reddit's own default comment sort, not raw score: a 95-up/5-down reply outranks a 10-up/0-down one
    // despite the smaller net score, because the larger sample gives more confidence in the ratio.
    // The NOT EXISTS/HiddenItem clause is the same viewer-scoped, ArchUnit-invisible JPQL pattern used in
    // PostRepository — see its comment. viewerId is null for an unauthenticated request.
    // No "AND c.removed = false" here (unlike before F3): a removed root comment must still appear — as
    // "[removed]", see CommentView — so any replies underneath it stay attached in the nested tree F3
    // builds (CommentService.findCommentTree). Excluding the row entirely would silently orphan its whole
    // reply subtree once replies became readable at all, which they weren't until F3.
    @Query("""
            SELECT c FROM Comment c
            WHERE c.postId = :postId AND c.parentId IS NULL
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            ORDER BY c.bestRank DESC, c.id DESC
            """)
    List<Comment> findTopLevelByBest(@Param("postId") UUID postId, @Param("viewerId") UUID viewerId, Pageable limit); // capped, never unbounded

    @Query("""
            SELECT c FROM Comment c
            WHERE c.postId = :postId AND c.parentId IS NULL
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            ORDER BY c.score DESC, c.id DESC
            """)
    List<Comment> findTopLevelByTop(@Param("postId") UUID postId, @Param("viewerId") UUID viewerId, Pageable limit);

    @Query("""
            SELECT c FROM Comment c
            WHERE c.postId = :postId AND c.parentId IS NULL
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            ORDER BY c.createdAt DESC, c.id DESC
            """)
    List<Comment> findTopLevelByNew(@Param("postId") UUID postId, @Param("viewerId") UUID viewerId, Pageable limit);

    // Ascending throughout (not mixed with a descending tiebreaker) so a future keyset cursor over this
    // sort has a consistent direction to compare against — see the comment sort feature's plan.
    @Query("""
            SELECT c FROM Comment c
            WHERE c.postId = :postId AND c.parentId IS NULL
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            ORDER BY c.createdAt ASC, c.id ASC
            """)
    List<Comment> findTopLevelByOld(@Param("postId") UUID postId, @Param("viewerId") UUID viewerId, Pageable limit);

    @Query("""
            SELECT c FROM Comment c
            WHERE c.postId = :postId AND c.parentId IS NULL
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            ORDER BY c.controversialRank DESC, c.id DESC
            """)
    List<Comment> findTopLevelByControversial(@Param("postId") UUID postId, @Param("viewerId") UUID viewerId, Pageable limit);

    // Every non-root comment for the post, unfiltered by `removed` for the same reason as the root queries
    // above. No pagination and no ORDER BY — CommentService.findCommentTree groups these by parentId and
    // sorts each sibling group in Java with the comparator matching the chosen sort, so the DB order here
    // doesn't matter. Unbounded is an accepted, deliberate scope boundary (bounded naturally by the
    // existing MAX_DEPTH=10 cap on reply depth) — real pagination/lazy-loading for huge threads is still
    // backend feature 8's job, not this one's.
    @Query("""
            SELECT c FROM Comment c
            WHERE c.postId = :postId AND c.parentId IS NOT NULL
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            """)
    List<Comment> findRepliesByPostId(@Param("postId") UUID postId, @Param("viewerId") UUID viewerId);

    // clearAutomatically: without it, a `parent` entity already loaded in this transaction (see
    // CommentService.reply) keeps its stale pre-increment childCount in the persistence context, and a
    // later read or an unrelated dirty-checked flush of that entity would silently clobber this update.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Comment c SET c.childCount = c.childCount + 1 WHERE c.id = :id")
    void incrementChildCount(@Param("id") UUID id);

    @Query("SELECT c.id AS id, c.authorId AS authorId FROM Comment c WHERE c.id IN :ids")
    List<CommentAuthorProjection> findAuthorIdsByIds(@Param("ids") Set<UUID> ids);

    // A user's "comments" profile tab (F7) — every comment they've made, top-level or reply (unlike the
    // per-post root queries above, there's no parentId IS NULL restriction: Reddit's own Comments tab
    // shows replies too). Same HiddenItem viewer filter and unremoved-only convention as every other
    // listing query in this codebase.
    // Private-community exclusion: same bug class and fix as post.PostRepository's findByAuthorId/
    // "All" queries — this is a public, cross-community listing with no filter on the comment's own
    // community, so a comment made in a private community the viewer isn't a member/moderator of must be
    // excluded. Comment only carries postId, not communityId, so this goes one hop further than the post
    // side: Post/Community/Membership/CommunityModerator are all referenced as bare JPQL entity names
    // (the comment module already legitimately depends on post at the Java level via PostService, so this
    // is even more clearly safe than the equivalent post-side subquery — see that file's comment for the
    // no-Java-import, no-ModuleBoundaryTest-risk reasoning). A comment whose postId matches no real post
    // (comments.post_id has no FK constraint by design, see Phase 1's own notes) can't satisfy any of
    // these EXISTS checks either way, so it stays visible — same fail-open behavior as today for that
    // edge case, not a new risk.
    @Query("""
            SELECT c FROM Comment c
            WHERE c.authorId = :authorId AND c.removed = false
              AND (c.createdAt < :cursorCreatedAt OR (c.createdAt = :cursorCreatedAt AND c.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
              AND (NOT EXISTS (SELECT 1 FROM Post p, Community cm WHERE p.id = c.postId AND cm.id = p.communityId AND cm.type = 'private')
                   OR EXISTS (SELECT 1 FROM Post p, Membership m WHERE p.id = c.postId AND m.communityId = p.communityId AND m.userId = :viewerId)
                   OR EXISTS (SELECT 1 FROM Post p, CommunityModerator cmod WHERE p.id = c.postId AND cmod.communityId = p.communityId AND cmod.userId = :viewerId))
            ORDER BY c.createdAt DESC, c.id DESC
            """)
    List<Comment> findByAuthorId(@Param("authorId") UUID authorId,
                                  @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                  @Param("cursorId") UUID cursorId,
                                  @Param("viewerId") UUID viewerId,
                                  Pageable limit);

    interface CommentAuthorProjection {
        UUID getId();

        UUID getAuthorId();
    }
}
