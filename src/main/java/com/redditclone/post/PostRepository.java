package com.redditclone.post;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
