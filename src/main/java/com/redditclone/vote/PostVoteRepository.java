package com.redditclone.vote;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface PostVoteRepository extends JpaRepository<PostVote, PostVoteId> {

    @Modifying
    @Query(value = """
            INSERT INTO post_votes (user_id, post_id, direction, voted_at)
            VALUES (:userId, :postId, :direction, now())
            ON CONFLICT (user_id, post_id) DO UPDATE SET direction = :direction, voted_at = now()
            """, nativeQuery = true)
    void upsert(@Param("userId") UUID userId, @Param("postId") UUID postId, @Param("direction") short direction);

    void deleteByUserIdAndPostId(UUID userId, UUID postId);
}
