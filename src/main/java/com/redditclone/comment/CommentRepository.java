package com.redditclone.comment;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    @Query("""
            SELECT c FROM Comment c
            WHERE c.postId = :postId AND c.parentId IS NULL AND c.removed = false
            ORDER BY c.score DESC
            """)
    List<Comment> findTopLevel(@Param("postId") UUID postId, Pageable limit); // capped, never unbounded

    // clearAutomatically: without it, a `parent` entity already loaded in this transaction (see
    // CommentService.reply) keeps its stale pre-increment childCount in the persistence context, and a
    // later read or an unrelated dirty-checked flush of that entity would silently clobber this update.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Comment c SET c.childCount = c.childCount + 1 WHERE c.id = :id")
    void incrementChildCount(@Param("id") UUID id);
}
