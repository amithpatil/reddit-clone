package com.redditclone.message;

import com.redditclone.auth.AuthService;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.common.text.Sanitizer;
import com.redditclone.notify.NotificationService;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class MessageService {

    private static final int PAGE_SIZE = 50;

    private final MessageRepository messages;
    private final AuthService authService;
    private final NotificationService notificationService;
    private final Sanitizer sanitizer;
    private final UuidV7Generator ids;

    public MessageService(MessageRepository messages, AuthService authService,
                           NotificationService notificationService, Sanitizer sanitizer, UuidV7Generator ids) {
        this.messages = messages;
        this.authService = authService;
        this.notificationService = notificationService;
        this.sanitizer = sanitizer;
        this.ids = ids;
    }

    @Transactional
    public Message send(UUID senderId, String recipientUsername, String subject, String body) {
        UUID recipientId = authService.findUserIdByUsername(recipientUsername)
                .orElseThrow(() -> new NotFoundException("no such user"));
        Message m = new Message();
        m.setId(ids.nextId());
        m.setSenderId(senderId);
        m.setRecipientId(recipientId);
        m.setSubject(subject);
        m.setBody(sanitizer.sanitize(body));
        return messages.save(m);
    }

    // Reddit's real inbox interleaves notifications and private messages into one feed, newest first —
    // merges MessageService's own page with NotificationService's public listForUser (its service, not
    // its repository, so this stays a one-directional message -> notify dependency with no
    // ModuleBoundaryTest conflict).
    public List<InboxItem> inbox(UUID userId) {
        List<InboxItem> messageItems = messages.findByRecipientIdOrderByCreatedAtDesc(userId, Pageable.ofSize(PAGE_SIZE))
                .stream().map(m -> new InboxItem("message", m.getCreatedAt(), m)).toList();
        List<InboxItem> notificationItems = notificationService.listForUser(userId)
                .stream().map(n -> new InboxItem("notification", n.getCreatedAt(), n)).toList();
        return Stream.concat(messageItems.stream(), notificationItems.stream())
                .sorted(Comparator.comparing(InboxItem::createdAt).reversed())
                .limit(PAGE_SIZE)
                .toList();
    }

    @Transactional
    public void markRead(UUID userId, UUID messageId) {
        int updated = messages.markRead(messageId, userId, Instant.now());
        if (updated == 0) {
            throw new NotFoundException("message not found");
        }
    }
}
