package com.redditclone.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChatRoomSummary(UUID roomId, List<String> otherParticipants, String lastMessageBody,
                               UUID lastMessageSenderId, Instant lastMessageAt, long unreadCount) {
}
