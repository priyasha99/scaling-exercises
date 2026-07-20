package com.scaling.exercise.security;

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
 * Spring Security configuration — STATELESS.
 *
 * KEY CONFIGURATION:
 *
 * 1. SessionCreationPolicy.STATELESS
 *    This is the most important line. It tells Spring Security to NEVER
 *    create an HttpSession. No JSESSIONID cookie. No server-side session.
 *    Every request must carry its own credentials (the JWT token).
 *
 *    Without this, Spring Security defaults to creating sessions, which
 *    defeats the purpose of JWT — you'd have both a token AND a session,
 *    and the session would only work on one server.
 *
 * 2. Endpoint security rules:
 *    - /api/auth/** → public (login, register, refresh)
 *    - /api/products/health, /api/products/cache-stats → public (monitoring)
 *    - GET /api/products/** → public (read product data)
 *    - POST /api/products → authenticated (create product)
 *    - DELETE /api/products/** → ADMIN only (Part C)
 *    - /api/admin/** → ADMIN only (Part C)
 *
 * 3. JWT filter added BEFORE Spring's UsernamePasswordAuthenticationFilter.
 *    This means our filter runs first, extracts the JWT, and sets the
 *    Security context. By the time Spring's filter chain continues,
 *    the user is already authenticated (or not).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF — not needed with JWT (no cookies, no session)
                .csrf(csrf -> csrf.disable())

                // STATELESS — the core of this exercise
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Endpoint security rules
                .authorizeHttpRequests(auth -> auth
                        // Auth endpoints are public
                        .requestMatchers("/api/auth/**").permitAll()

                        // Health and cache-stats are public (monitoring)
                        .requestMatchers("/api/products/health").permitAll()
                        .requestMatchers("/api/products/cache-stats").permitAll()

                        // GET requests for products are public (read-only API)
                        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()

                        // Admin endpoints require ADMIN role (Part C)
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )

                // Add our JWT filter before Spring's default auth filter
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
