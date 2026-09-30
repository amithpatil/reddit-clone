package com.redditclone.chat.dto;

import java.util.UUID;

// Used by ChatStompHandler's @MessageMapping, not a REST controller — no Bean Validation annotations here
// since STOMP payload deserialization doesn't run through Jakarta Validation the way @Valid @RequestBody
// does; ChatService.send() validates roomId/body itself.
public record SendMessageRequest(UUID roomId, String body) {
}
