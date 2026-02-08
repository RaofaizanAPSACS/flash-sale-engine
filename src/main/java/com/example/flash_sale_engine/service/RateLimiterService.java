package com.example.flash_sale_engine.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * Redis-based sliding window rate limiter.
 *
 * Uses a sorted set per user with timestamps as scores.
 * Lua script ensures atomicity: count requests in window, reject if over limit.
 *
 * This is the first line of defense against traffic spikes.
 * Excess requests are rejected with 429 before they ever reach the stock check.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimiterService {

    private static final String RATE_KEY_PREFIX = "rate:";
    private static final String GLOBAL_RATE_KEY = "rate:global";

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;

    @Value("${flash-sale.rate-limit.requests-per-second:5}")
    private int requestsPerSecond;

    @Value("${flash-sale.rate-limit.global-requests-per-second:50000}")
    private int globalRequestsPerSecond;

    /**
     * Check if a user is within rate limits.
     *
     * @param userId the user identifier
     * @return true if request is allowed, false if rate limited
     */
    public boolean isAllowed(String userId) {
        long now = System.currentTimeMillis();

        // Per-user rate limit
        String userKey = RATE_KEY_PREFIX + userId;
        Long userResult = redisTemplate.execute(
                rateLimitScript,
                Collections.singletonList(userKey),
                "1",                                    // 1-second window
                String.valueOf(requestsPerSecond),      // max requests
                String.valueOf(now)                     // current time
        );

        if (userResult == null || userResult == 0) {
            log.debug("Rate limited user: {}", userId);
            return false;
        }

        // Global rate limit
        Long globalResult = redisTemplate.execute(
                rateLimitScript,
                Collections.singletonList(GLOBAL_RATE_KEY),
                "1",                                         // 1-second window
                String.valueOf(globalRequestsPerSecond),     // max global requests
                String.valueOf(now)                          // current time
        );

        if (globalResult == null || globalResult == 0) {
            log.warn("Global rate limit reached");
            return false;
        }

        return true;
    }
}
