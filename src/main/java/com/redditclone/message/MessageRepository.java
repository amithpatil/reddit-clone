package com.redditclone.message;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId, Pageable pageable);

    @Modifying
    @Query("UPDATE Message m SET m.readAt = :readAt WHERE m.id = :id AND m.recipientId = :recipientId")
    int markRead(@Param("id") UUID id, @Param("recipientId") UUID recipientId, @Param("readAt") Instant readAt);
}
