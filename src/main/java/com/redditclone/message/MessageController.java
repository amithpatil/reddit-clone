package com.redditclone.message;

import com.redditclone.message.dto.ComposeRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class MessageController {

    private final MessageService messages;

    public MessageController(MessageService messages) {
        this.messages = messages;
    }

    @PostMapping("/api/compose")
    public Message compose(@AuthenticationPrincipal UUID userId, @Valid @RequestBody ComposeRequest req) {
        return messages.send(userId, req.recipientUsername(), req.subject(), req.body());
    }

    @GetMapping("/message/inbox")
    public List<InboxItem> inbox(@AuthenticationPrincipal UUID userId) {
        return messages.inbox(userId);
    }

    @PostMapping("/api/messages/{id}/read")
    public void markRead(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        messages.markRead(userId, id);
    }
}
