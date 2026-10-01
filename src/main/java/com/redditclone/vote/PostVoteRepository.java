package com.redditclone.vote;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PostVoteRepository extends JpaRepository<PostVote, PostVoteId> {

    // Read by VoteService.getMyPostVotes — a single batched IN query backing GET /api/vote/mine.
    List<PostVote> findByUserIdAndPostIdIn(UUID userId, Collection<UUID> postIds);

    @Modifying
    @Query(value = """
            INSERT INTO post_votes (user_id, post_id, direction, voted_at)
            VALUES (:userId, :postId, :direction, now())
            ON CONFLICT (user_id, post_id) DO UPDATE SET direction = :direction, voted_at = now()
            """, nativeQuery = true)
    void upsert(@Param("userId") UUID userId, @Param("postId") UUID postId, @Param("direction") short direction);

    void deleteByUserIdAndPostId(UUID userId, UUID postId);
}
