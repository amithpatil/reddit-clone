package com.redditclone.notify;

import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {

    private static final int PAGE_SIZE = 50;

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    public List<Notification> listForUser(UUID userId) {
        return notifications.findByUserIdOrderByCreatedAtDesc(userId, Pageable.ofSize(PAGE_SIZE));
    }

    @Transactional
    public void markRead(UUID actorId, UUID notificationId) {
        Notification n = notifications.findByNotificationId(notificationId)
                .orElseThrow(() -> new NotFoundException("notification not found"));
        if (!n.getUserId().equals(actorId)) {
            throw new ForbiddenException("not your notification");
        }
        notifications.markRead(n.getId(), n.getCreatedAt(), Instant.now());
    }
}
