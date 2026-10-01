package com.redditclone.notify;

import com.redditclone.auth.AuthService;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.community.CommunityService;
import com.redditclone.post.Post;
import com.redditclone.post.PostService;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class NotificationService {

    private static final int PAGE_SIZE = 50;

    private final NotificationRepository notifications;
    private final PostService postService;
    private final CommunityService communityService;
    private final AuthService authService;
    private final ObjectMapper json;

    public NotificationService(NotificationRepository notifications, PostService postService,
                                CommunityService communityService, AuthService authService, ObjectMapper json) {
        this.notifications = notifications;
        this.postService = postService;
        this.communityService = communityService;
        this.authService = authService;
        this.json = json;
    }

    public List<Notification> listForUser(UUID userId) {
        List<Notification> page = notifications.findByUserIdOrderByCreatedAtDesc(userId, Pageable.ofSize(PAGE_SIZE));
        attachDisplay(page);
        return page;
    }

    // Same @Transient attach-plus-batched-lookup pattern as ModerationService.listModQueue's
    // attachPreviews (F8): parse every row's source once, batch-resolve posts/communities/actors in one
    // call each, then set the display fields per row — never a lookup per row.
    private void attachDisplay(List<Notification> page) {
        if (page.isEmpty()) {
            return;
        }
        Map<Notification, JsonNode> sources = page.stream()
                .collect(Collectors.toMap(n -> n, n -> json.readTree(n.getSource())));

        Set<UUID> postIds = new HashSet<>();
        Set<UUID> communityIds = new HashSet<>();
        Set<UUID> actorIds = new HashSet<>();
        for (JsonNode source : sources.values()) {
            if (source.has("postId")) {
                postIds.add(UUID.fromString(source.path("postId").asString()));
            }
            if (source.has("communityId")) {
                communityIds.add(UUID.fromString(source.path("communityId").asString()));
            }
            if (source.has("actorId")) {
                actorIds.add(UUID.fromString(source.path("actorId").asString()));
            }
            if (source.has("senderId")) {
                actorIds.add(UUID.fromString(source.path("senderId").asString()));
            }
        }

        Map<UUID, Post> postsById = postService.findAllByIds(postIds).stream()
                .collect(Collectors.toMap(Post::getId, p -> p));
        Map<UUID, String> communityNames = communityService.findNamesByIds(communityIds);
        Map<UUID, String> usernames = authService.findUsernamesByIds(actorIds);

        for (Map.Entry<Notification, JsonNode> entry : sources.entrySet()) {
            Notification n = entry.getKey();
            JsonNode source = entry.getValue();
            if (source.has("postId")) {
                Post post = postsById.get(UUID.fromString(source.path("postId").asString()));
                n.setPostTitle(post != null ? post.getTitle() : null);
            }
            if (source.has("communityId")) {
                n.setCommunityName(communityNames.get(UUID.fromString(source.path("communityId").asString())));
            }
            if (source.has("actorId")) {
                n.setActorUsername(usernames.get(UUID.fromString(source.path("actorId").asString())));
            } else if (source.has("senderId")) {
                n.setActorUsername(usernames.get(UUID.fromString(source.path("senderId").asString())));
            }
        }
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

    @Transactional
    public void markAllRead(UUID userId) {
        notifications.markAllRead(userId, Instant.now());
    }
}
