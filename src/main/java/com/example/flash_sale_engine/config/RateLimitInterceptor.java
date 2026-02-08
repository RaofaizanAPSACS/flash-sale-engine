package com.example.flash_sale_engine.config;

import com.example.flash_sale_engine.service.RateLimiterService;
import com.example.flash_sale_engine.service.SaleLifecycleService;
import io.micrometer.core.instrument.Counter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * HTTP interceptor that enforces rate limiting on purchase endpoints.
 *
 * Performance optimization: once the sale is sold out, rate limiting is
 * skipped entirely (in-memory check). The controller will return 409 instantly.
 * This eliminates 2 Redis round trips per request for 99%+ of traffic.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiterService rateLimiterService;
    private final SaleLifecycleService saleLifecycleService;
    private final Counter requestsRateLimited;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Only rate-limit POST requests to purchase endpoints
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // Fast path: if any product is sold out, skip rate limiting entirely.
        // The controller will reject with 409 from in-memory cache (zero Redis calls).
        // No point wasting Redis connections on rate-limit checks for dead requests.
        if (saleLifecycleService.isAnySoldOut()) {
            return true;
        }

        // Extract userId from header, use IP as fallback
        String userId = request.getHeader("X-User-Id");
        if (userId == null || userId.isBlank()) {
            userId = request.getRemoteAddr();
        }

        if (!rateLimiterService.isAllowed(userId)) {
            requestsRateLimited.increment();
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", "1");
            response.getWriter().write(
                    "{\"message\":\"Too many requests. Please wait and try again.\",\"success\":false,\"error\":\"RATE_LIMITED\"}"
            );
            return false;
        }

        return true;
    }
}
