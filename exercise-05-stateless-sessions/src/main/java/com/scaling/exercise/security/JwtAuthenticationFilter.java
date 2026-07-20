package com.scaling.exercise.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT authentication filter — runs on every request.
 *
 * HOW IT WORKS:
 * 1. Extract the token from the Authorization header
 *    (format: "Bearer eyJhbGciOiJIUzI1NiJ9...")
 * 2. Validate the token (signature, expiration, blacklist)
 * 3. If valid, set the user in Spring Security's context
 * 4. If invalid or missing, let the request continue unauthenticated
 *    (Spring Security will return 401 for protected endpoints)
 *
 * WHY THIS IS STATELESS:
 * Traditional Spring Security stores the authenticated user in an
 * HttpSession (server-side, in-memory). If the next request goes to
 * a different server, the session doesn't exist there.
 *
 * This filter doesn't use sessions at all. It validates the JWT on
 * every request. The token itself IS the session — it travels with
 * the request, and any server can verify it.
 *
 * NO DATABASE LOOKUP:
 * Notice we never call userRepository.findByUsername(). The token
 * contains everything we need (username, role). We trust it because
 * the signature proves it was issued by us and hasn't been tampered with.
 * This saves a database round-trip on every authenticated request.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final String serverId;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
        String hostname;
        try {
            hostname = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        this.serverId = hostname;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Set X-Server-Id on EVERY response — shows which server handled it
        response.setHeader("X-Server-Id", serverId);

        String authHeader = request.getHeader("Authorization");

        // No token? Let the request continue unauthenticated.
        // Spring Security will handle 401 for protected endpoints.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7); // Remove "Bearer " prefix
        Claims claims = jwtService.validateToken(token);

        if (claims != null) {
            // Only allow access tokens for API access (not refresh tokens)
            String tokenType = claims.get("type", String.class);
            if (!"access".equals(tokenType)) {
                filterChain.doFilter(request, response);
                return;
            }

            String username = claims.getSubject();
            String role = claims.get("role", String.class);

            // Set the authenticated user in Spring Security's context
            // The "ROLE_" prefix is a Spring Security convention
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            username,
                            null, // No credentials needed — the token is the proof
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    );

            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Add headers for debugging — see which user and which server
            response.setHeader("X-Auth-User", username);
            response.setHeader("X-Auth-Role", role);
        }

        filterChain.doFilter(request, response);
    }
}
