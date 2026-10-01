package com.redditclone.notify;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/api/notifications")
    public List<Notification> list(@AuthenticationPrincipal UUID userId) {
        return notificationService.listForUser(userId);
    }

    @PostMapping("/api/notifications/{id}/read")
    public void markRead(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        notificationService.markRead(userId, id);
    }

    @PostMapping("/api/notifications/read-all")
    public void markAllRead(@AuthenticationPrincipal UUID userId) {
        notificationService.markAllRead(userId);
    }
}
