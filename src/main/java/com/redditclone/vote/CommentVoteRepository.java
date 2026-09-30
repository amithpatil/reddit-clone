package com.redditclone.vote;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface CommentVoteRepository extends JpaRepository<CommentVote, CommentVoteId> {

    @Modifying
    @Query(value = """
            INSERT INTO comment_votes (user_id, comment_id, direction, voted_at)
            VALUES (:userId, :commentId, :direction, now())
            ON CONFLICT (user_id, comment_id) DO UPDATE SET direction = :direction, voted_at = now()
            """, nativeQuery = true)
    void upsert(@Param("userId") UUID userId, @Param("commentId") UUID commentId, @Param("direction") short direction);

    void deleteByUserIdAndCommentId(UUID userId, UUID commentId);
}
