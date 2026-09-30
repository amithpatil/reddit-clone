package com.redditclone.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    // Same keyset-pagination shape as PostRepository.findNewPage — (created_at, id) DESC, never OFFSET.
    @Query("""
            SELECT m FROM ChatMessage m
            WHERE m.roomId = :roomId
              AND (m.createdAt < :cursorCreatedAt OR (m.createdAt = :cursorCreatedAt AND m.id < :cursorId))
            ORDER BY m.createdAt DESC, m.id DESC
            """)
    List<ChatMessage> findPage(@Param("roomId") UUID roomId,
                                @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                @Param("cursorId") UUID cursorId,
                                Pageable limit);
}
