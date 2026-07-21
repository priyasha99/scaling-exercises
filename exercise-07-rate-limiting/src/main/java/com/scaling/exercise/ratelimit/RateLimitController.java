package com.scaling.exercise.ratelimit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rate limiting monitoring endpoints.
 *
 * GET /api/rate-limit/metrics — rejection stats
 * GET /api/rate-limit/config  — current rate limit configuration
 *
 * These endpoints are excluded from rate limiting
 * (see RateLimitFilter.shouldNotFilter).
 */
@RestController
@RequestMapping("/api/rate-limit")
public class RateLimitController {

    private final RateLimitConfig config;
    private final String serverId;

    public RateLimitController(RateLimitConfig config) {
        this.config = config;
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        this.serverId = hostname;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverId", serverId);
        result.put("enabled", config.isEnabled());
        result.put("metrics", RateLimitMetrics.getStats());
        return result;
    }

    @GetMapping("/config")
    public Map<String, Object> currentConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", config.isEnabled());

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("capacity", config.getUserCapacity());
        user.put("refillRate", config.getUserRefillRate());
        user.put("description", config.getUserCapacity() + " burst, " + config.getUserRefillRate() + " req/s sustained");
        result.put("perUser", user);

        Map<String, Object> admin = new LinkedHashMap<>();
        admin.put("capacity", config.getAdminCapacity());
        admin.put("refillRate", config.getAdminRefillRate());
        admin.put("description", config.getAdminCapacity() + " burst, " + config.getAdminRefillRate() + " req/s sustained");
        result.put("perAdmin", admin);

        Map<String, Object> ip = new LinkedHashMap<>();
        ip.put("capacity", config.getIpCapacity());
        ip.put("refillRate", config.getIpRefillRate());
        ip.put("description", config.getIpCapacity() + " burst, " + config.getIpRefillRate() + " req/s sustained");
        result.put("perIp", ip);

        Map<String, Object> global = new LinkedHashMap<>();
        global.put("capacity", config.getGlobalCapacity());
        global.put("refillRate", config.getGlobalRefillRate());
        global.put("description", config.getGlobalCapacity() + " burst, " + config.getGlobalRefillRate() + " req/s sustained");
        result.put("global", global);

        return result;
    }
}
