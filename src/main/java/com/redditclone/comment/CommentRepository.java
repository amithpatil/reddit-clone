package com.redditclone.comment;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    // Ordered by best_rank (Wilson score lower bound on the up/down split, see RankFormulas.bestRank) —
    // Reddit's own default comment sort, not raw score: a 95-up/5-down reply outranks a 10-up/0-down one
    // despite the smaller net score, because the larger sample gives more confidence in the ratio.
    // The NOT EXISTS/HiddenItem clause is the same viewer-scoped, ArchUnit-invisible JPQL pattern used in
    // PostRepository — see its comment. viewerId is null for an unauthenticated request.
    @Query("""
            SELECT c FROM Comment c
            WHERE c.postId = :postId AND c.parentId IS NULL AND c.removed = false
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            ORDER BY c.bestRank DESC, c.id DESC
            """)
    List<Comment> findTopLevel(@Param("postId") UUID postId, @Param("viewerId") UUID viewerId, Pageable limit); // capped, never unbounded

    // clearAutomatically: without it, a `parent` entity already loaded in this transaction (see
    // CommentService.reply) keeps its stale pre-increment childCount in the persistence context, and a
    // later read or an unrelated dirty-checked flush of that entity would silently clobber this update.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Comment c SET c.childCount = c.childCount + 1 WHERE c.id = :id")
    void incrementChildCount(@Param("id") UUID id);

    @Query("SELECT c.id AS id, c.authorId AS authorId FROM Comment c WHERE c.id IN :ids")
    List<CommentAuthorProjection> findAuthorIdsByIds(@Param("ids") Set<UUID> ids);

    interface CommentAuthorProjection {
        UUID getId();

        UUID getAuthorId();
    }
}
