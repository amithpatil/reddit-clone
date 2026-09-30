package com.redditclone.chat;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class ChatRoomParticipantId implements Serializable {

    private UUID roomId;
    private UUID userId;

    public ChatRoomParticipantId() {
    }

    public ChatRoomParticipantId(UUID roomId, UUID userId) {
        this.roomId = roomId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChatRoomParticipantId that)) return false;
        return Objects.equals(roomId, that.roomId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roomId, userId);
    }
}
