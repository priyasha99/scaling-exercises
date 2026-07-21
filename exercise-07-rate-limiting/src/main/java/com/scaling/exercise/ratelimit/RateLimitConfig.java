package com.scaling.exercise.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Rate limit configuration — loaded from application.properties.
 *
 * THREE TIERS:
 *
 * 1. PER-USER (authenticated requests):
 *    Bucket key: "rate_limit::user::{username}"
 *    Each user gets their own bucket. Fair — one user can't exhaust
 *    the limit for everyone else.
 *
 * 2. PER-IP (unauthenticated requests):
 *    Bucket key: "rate_limit::ip::{ip_address}"
 *    Limits anonymous traffic by source IP. Prevents a single client
 *    from hammering public endpoints.
 *
 * 3. GLOBAL:
 *    Bucket key: "rate_limit::global"
 *    Overall system protection. Even if individual limits aren't
 *    exceeded, the total traffic might overwhelm the system.
 *    This is the backpressure mechanism — when the system is saturated,
 *    shed excess load regardless of who's sending it.
 *
 * ROLE-BASED TIERS (Part C):
 *    ADMIN users get higher per-user limits than regular users.
 *    Useful for internal tools, monitoring dashboards, etc.
 */
@Configuration
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitConfig {

    private boolean enabled = true;

    // Per-user limits (authenticated)
    private int userCapacity = 50;          // burst: 50 requests
    private double userRefillRate = 10.0;   // refill: 10 tokens/sec

    // Per-user limits for ADMIN role
    private int adminCapacity = 200;        // burst: 200 requests
    private double adminRefillRate = 50.0;  // refill: 50 tokens/sec

    // Per-IP limits (unauthenticated)
    private int ipCapacity = 30;            // burst: 30 requests
    private double ipRefillRate = 5.0;      // refill: 5 tokens/sec

    // Global limits (system-wide backpressure)
    private int globalCapacity = 500;       // burst: 500 requests
    private double globalRefillRate = 200.0; // refill: 200 tokens/sec

    // Getters and setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getUserCapacity() { return userCapacity; }
    public void setUserCapacity(int userCapacity) { this.userCapacity = userCapacity; }
    public double getUserRefillRate() { return userRefillRate; }
    public void setUserRefillRate(double userRefillRate) { this.userRefillRate = userRefillRate; }

    public int getAdminCapacity() { return adminCapacity; }
    public void setAdminCapacity(int adminCapacity) { this.adminCapacity = adminCapacity; }
    public double getAdminRefillRate() { return adminRefillRate; }
    public void setAdminRefillRate(double adminRefillRate) { this.adminRefillRate = adminRefillRate; }

    public int getIpCapacity() { return ipCapacity; }
    public void setIpCapacity(int ipCapacity) { this.ipCapacity = ipCapacity; }
    public double getIpRefillRate() { return ipRefillRate; }
    public void setIpRefillRate(double ipRefillRate) { this.ipRefillRate = ipRefillRate; }

    public int getGlobalCapacity() { return globalCapacity; }
    public void setGlobalCapacity(int globalCapacity) { this.globalCapacity = globalCapacity; }
    public double getGlobalRefillRate() { return globalRefillRate; }
    public void setGlobalRefillRate(double globalRefillRate) { this.globalRefillRate = globalRefillRate; }
}
