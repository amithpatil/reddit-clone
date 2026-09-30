package com.redditclone.post;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
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

    private static final Logger log = LoggerFactory.getLogger(FeedCacheService.class);

    private final StringRedisTemplate redis;
    private final Cache<String, String> local = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(5)).maximumSize(1000).build();

    public FeedCacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // Before this cache existed, /hot was a pure DB read with no Redis dependency — both methods below
    // fail open (log and fall back to a cache miss) rather than let a Redis blip turn a working DB-backed
    // endpoint into a 500.
    public Optional<String> getHotPage(String communityName) {
        String cacheKey = key(communityName);
        String cached = local.getIfPresent(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            String fromRedis = redis.opsForValue().get(cacheKey);
            if (fromRedis != null) {
                local.put(cacheKey, fromRedis);
            }
            return Optional.ofNullable(fromRedis);
        } catch (DataAccessException e) {
            log.warn("feed cache read failed, falling back to DB", e);
            return Optional.empty();
        }
    }

    public void putHotPage(String communityName, String json) {
        String cacheKey = key(communityName);
        try {
            redis.opsForValue().set(cacheKey, json, Duration.ofSeconds(45));
        } catch (DataAccessException e) {
            log.warn("feed cache write failed, skipping cache", e);
        }
        local.put(cacheKey, json);
    }

    // communities.name is citext (case-insensitive), but this key is built from the raw path variable —
    // lowercasing it so /r/AskReddit/hot and /r/askreddit/hot share one cache entry instead of each
    // casing getting its own independently-stale copy.
    private String key(String communityName) {
        return "feed:hot:" + communityName.toLowerCase();
    }
}
