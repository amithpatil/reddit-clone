package com.redditclone.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatRoomParticipantRepository extends JpaRepository<ChatRoomParticipant, ChatRoomParticipantId> {

    boolean existsByRoomIdAndUserId(UUID roomId, UUID userId);

    Optional<ChatRoomParticipant> findByRoomIdAndUserId(UUID roomId, UUID userId);

    List<ChatRoomParticipant> findByRoomId(UUID roomId);

    List<ChatRoomParticipant> findByUserId(UUID userId);
}
