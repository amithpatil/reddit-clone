package com.redditclone.post;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

// Caffeine (L1, per-instance) in front of Redis (L2), only for page 1 of /hot (no `after` cursor — see
// PostController.listHot) since that's where virtually all real traffic lands; deeper pages always hit
// the DB directly. 45s Redis TTL, no active invalidation on vote — an accepted staleness window, not a
// bug (matches the source plan's own stated trade-off).
@Service
public class FeedCacheService {

    private final StringRedisTemplate redis;
    private final Cache<String, String> local = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(5)).maximumSize(1000).build();

    public FeedCacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<String> getHotPage(String communityName) {
        String cached = local.getIfPresent(key(communityName));
        if (cached != null) {
            return Optional.of(cached);
        }
        String fromRedis = redis.opsForValue().get(key(communityName));
        if (fromRedis != null) {
            local.put(key(communityName), fromRedis);
        }
        return Optional.ofNullable(fromRedis);
    }

    public void putHotPage(String communityName, String json) {
        redis.opsForValue().set(key(communityName), json, Duration.ofSeconds(45));
        local.put(key(communityName), json);
    }

    private String key(String communityName) {
        return "feed:hot:" + communityName;
    }
}
