package com.redditclone.common.ratelimit;

import com.redditclone.common.exception.TooManyRequestsException;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

// Generic, reusable rate-limiting primitive — call checkLimit from any controller/service that needs to
// throttle a specific identity (an IP, a user id, ...) against a specific named bucket. Currently used by
// AuthController for login/register; not wired into any other endpoint yet (see feature 9's plan for why).
@Service
public class RateLimiter {

    private final ProxyManager<byte[]> proxyManager;

    public RateLimiter(ProxyManager<byte[]> proxyManager) {
        this.proxyManager = proxyManager;
    }

    // refillIntervally (not refillGreedy): once a bucket is exhausted, the full capacity only comes back
    // after one whole period elapses, rather than trickling back continuously — greedy refill would let an
    // attacker pace requests just slowly enough to never actually get blocked for long, which defeats the
    // point for a brute-force-style limiter.
    public void checkLimit(String bucketName, String key, int capacity, Duration period) {
        byte[] redisKey = ("ratelimit:" + bucketName + ":" + key).getBytes(StandardCharsets.UTF_8);
        Supplier<BucketConfiguration> config = () -> BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(capacity).refillIntervally(capacity, period))
                .build();
        Bucket bucket = proxyManager.getProxy(redisKey, config);
        if (!bucket.tryConsume(1)) {
            throw new TooManyRequestsException("too many attempts, try again later");
        }
    }
}
