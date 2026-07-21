package com.scaling.exercise.security;

import com.scaling.exercise.ratelimit.RateLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration — STATELESS + Rate Limiting + Sharding.
 *
 * FILTER ORDER (Exercise 08):
 *   1. JwtAuthenticationFilter — extracts JWT, sets SecurityContext
 *   2. RateLimitFilter — checks token bucket, returns 429 if exceeded
 *   3. Spring Security authorization — checks endpoint permissions
 *   4. Controller — handles the request (routes to correct shard)
 *
 * The rate limit filter runs AFTER JWT so it has access to the
 * authenticated username for per-user rate limiting.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RateLimitFilter rateLimitFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())

                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()

                        // Monitoring endpoints — public, excluded from rate limiting
                        .requestMatchers("/api/products/health").permitAll()
                        .requestMatchers("/api/products/cache-stats").permitAll()
                        .requestMatchers("/api/rate-limit/**").permitAll()
                        .requestMatchers("/api/shard/**").permitAll()

                        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()

                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        .anyRequest().authenticated()
                )

                // Filter order: JWT first (sets auth context), then rate limit
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter,
                        JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Prevent the servlet container from auto-registering the RateLimitFilter
     * as a standalone filter. We only want it in the Spring Security chain
     * (via addFilterAfter above), not as a separate servlet filter that runs
     * on every request independently — that would cause double execution.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
