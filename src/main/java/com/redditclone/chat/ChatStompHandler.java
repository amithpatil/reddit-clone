package com.redditclone.chat;

import com.redditclone.chat.dto.SendMessageRequest;
import com.redditclone.common.OutboxWriter;
import com.redditclone.common.correlation.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@Controller
public class ChatStompHandler {

    private final ChatService chat;
    private final SimpMessagingTemplate messagingTemplate;
    private final OutboxWriter outboxWriter;

    public ChatStompHandler(ChatService chat, SimpMessagingTemplate messagingTemplate, OutboxWriter outboxWriter) {
        this.chat = chat;
        this.messagingTemplate = messagingTemplate;
        this.outboxWriter = outboxWriter;
    }

    @MessageMapping("/chat.send")
    public void send(SendMessageRequest req, Principal principal) {
        // STOMP frames arrive on Spring's message-broker inbound-channel thread, not an HTTP thread, so
        // there's no CorrelationIdFilter-set value to inherit — each SEND frame is its own logical action
        // (not tied to the WebSocket CONNECT), so it mints a fresh id, same as a fresh HTTP request would.
        // outboxWriter.writeEvent below just reads MDC at call time, same mechanism as the HTTP path.
        MDC.put(CorrelationIdFilter.MDC_KEY, UUID.randomUUID().toString());
        try {
            UUID senderId = UUID.fromString(principal.getName());
            ChatMessage saved = chat.send(senderId, req.roomId(), req.body());
            for (UUID otherId : chat.otherParticipantIds(req.roomId(), senderId)) {
                messagingTemplate.convertAndSendToUser(otherId.toString(), "/queue/chat", saved);
                // Reuses the existing, fully generic notify.NotificationOutboxWorker pattern with zero changes
                // to it — a message is never silently missed just because the recipient wasn't connected at
                // the moment it was sent; the persisted row + REST history endpoint is the real "offline
                // queue," this push is a live convenience on top of that, not the only delivery path.
                outboxWriter.writeEvent("notification", Map.of(
                        "userId", otherId,
                        "type", "chat_message",
                        "source", Map.of("roomId", req.roomId(), "senderId", senderId)));
            }
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    // A STOMP SEND frame has no HTTP-style status code to fail with — validation/permission failures from
    // ChatService (NotFoundException/ForbiddenException/BadRequestException, all RuntimeExceptions) are
    // routed back to the sender's own error queue instead.
    @MessageExceptionHandler
    @SendToUser("/queue/errors")
    public String handleException(Exception e) {
        return e.getMessage();
    }
}
