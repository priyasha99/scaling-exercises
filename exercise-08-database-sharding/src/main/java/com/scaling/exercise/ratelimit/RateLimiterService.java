package com.scaling.exercise.ratelimit;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Distributed rate limiter using Redis + Lua token bucket.
 *
 * WHY DISTRIBUTED?
 * We have 3 app servers behind a load balancer. If each server tracked
 * rate limits independently, a user could make 100 requests to each
 * server = 300 total while thinking the limit is 100. By storing the
 * bucket in Redis, all servers share the same counter.
 *
 * WHY LUA?
 * The token bucket algorithm requires read-modify-write on the bucket
 * state. If two requests arrive simultaneously on different servers,
 * both read "50 tokens left," both deduct 1, both write "49 tokens."
 * One request got a free pass. Lua scripts execute atomically in Redis
 * — no race condition possible.
 *
 * HOW IT WORKS:
 *   1. Filter intercepts request
 *   2. Determines the rate limit key (per-user, per-IP, or global)
 *   3. Calls this service with the key and limit config
 *   4. Lua script checks the bucket and returns allowed/denied
 *   5. Filter either passes the request through or returns 429
 */
@Service
public class RateLimiterService {

    private final StringRedisTemplate redisTemplate;
    private DefaultRedisScript<List> tokenBucketScript;

    public RateLimiterService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        tokenBucketScript = new DefaultRedisScript<>();
        tokenBucketScript.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        tokenBucketScript.setResultType(List.class);
    }

    /**
     * Check if a request is allowed under the rate limit.
     *
     * @param key       Redis key for this bucket (e.g., "rate_limit::user::alice")
     * @param capacity  Max tokens in the bucket (burst capacity)
     * @param refillRate Tokens added per second
     * @return RateLimitResult with allowed/denied, remaining tokens, retry-after
     */
    public RateLimitResult isAllowed(String key, int capacity, double refillRate) {
        double now = System.currentTimeMillis() / 1000.0;

        List<Long> result = redisTemplate.execute(
                tokenBucketScript,
                Collections.singletonList(key),
                String.valueOf(capacity),
                String.valueOf(refillRate),
                String.valueOf(now),
                "1"  // consume 1 token
        );

        if (result == null || result.size() < 3) {
            // Redis unavailable — fail open (allow the request)
            // This is a deliberate choice: if Redis is down, we'd rather
            // serve requests without rate limiting than block everything.
            return new RateLimitResult(true, 0, 0);
        }

        boolean allowed = result.get(0) == 1;
        long remaining = result.get(1);
        long retryAfterMs = result.get(2);

        return new RateLimitResult(allowed, remaining, retryAfterMs);
    }
}
