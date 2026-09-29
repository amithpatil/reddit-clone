package com.redditclone.post;

import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.common.text.Sanitizer;
import com.redditclone.post.dto.CreatePostRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PostService {

    private final PostRepository posts;
    private final UuidV7Generator ids;
    private final StringRedisTemplate redis;
    private final Sanitizer sanitizer;

    public PostService(PostRepository posts, UuidV7Generator ids, StringRedisTemplate redis, Sanitizer sanitizer) {
        this.posts = posts;
        this.ids = ids;
        this.redis = redis;
        this.sanitizer = sanitizer;
    }

    @Transactional
    public Post create(UUID authorId, UUID communityId, CreatePostRequest req, String idempotencyKey) {
        String key = "idempotency:" + authorId + ":" + idempotencyKey;
        String existingPostId = redis.opsForValue().get(key);
        if (existingPostId != null) {
            return posts.findById(UUID.fromString(existingPostId))
                    .orElseThrow(() -> new NotFoundException("post not found"));
        }
        Post p = new Post();
        p.setId(ids.nextId());
        p.setCommunityId(communityId);
        p.setAuthorId(authorId);
        p.setKind(req.kind());
        p.setTitle(req.title());
        p.setBody(sanitizer.sanitize(req.body()));
        p.setUrl(req.url());
        posts.save(p);
        // SETNX so a concurrent retry can't race past the check above; 24h TTL is generous for a client retry window.
        redis.opsForValue().setIfAbsent(key, p.getId().toString(), Duration.ofHours(24));
        return p;
    }

    public List<Post> findNewPage(UUID communityId, Instant cursorCreatedAt, UUID cursorId, int limit) {
        return posts.findNewPage(communityId, cursorCreatedAt, cursorId, Pageable.ofSize(limit));
    }

    public Post findById(UUID postId) {
        return posts.findById(postId).orElseThrow(() -> new NotFoundException("post not found"));
    }
}
