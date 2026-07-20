package com.scaling.exercise.controller;

import com.scaling.exercise.model.User;
import com.scaling.exercise.repository.UserRepository;
import com.scaling.exercise.security.JwtService;
import io.jsonwebtoken.Claims;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authentication controller — login, register, refresh, logout.
 *
 * FLOW:
 *
 * 1. Register: POST /api/auth/register
 *    → Creates a user with BCrypt-hashed password
 *    → Returns access + refresh tokens
 *
 * 2. Login: POST /api/auth/login
 *    → Validates credentials against database
 *    → Returns access + refresh tokens
 *    → The access token is what you send with every API request
 *
 * 3. Refresh: POST /api/auth/refresh
 *    → Takes a refresh token, returns a new access token
 *    → Used when the access token expires (every 15 minutes)
 *    → The refresh token lasts 24 hours
 *
 * 4. Logout: POST /api/auth/logout (Part C)
 *    → Blacklists the current access token in Redis
 *    → Token can't be used again even though it hasn't expired
 *
 * WHY SEPARATE ACCESS AND REFRESH TOKENS:
 * Access tokens are short-lived (15 min) to limit damage if stolen.
 * But re-entering credentials every 15 minutes is terrible UX.
 * Refresh tokens solve this — they last 24 hours and are only sent
 * to the /refresh endpoint, reducing exposure.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final String serverId;

    public AuthController(UserRepository userRepository,
                          JwtService jwtService,
                          PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        this.serverId = hostname;
    }

    /**
     * Register a new user.
     *
     * POST /api/auth/register
     * Body: {"username": "alice", "password": "password123", "role": "USER"}
     *
     * Returns access + refresh tokens immediately (auto-login after register).
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");
        String role = request.getOrDefault("role", "USER");

        if (username == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "username and password are required"));
        }

        if (userRepository.existsByUsername(username)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "username already exists"));
        }

        // Only allow USER and ADMIN roles
        if (!"USER".equals(role) && !"ADMIN".equals(role)) {
            role = "USER";
        }

        User user = new User(username, passwordEncoder.encode(password), role);
        userRepository.save(user);

        String accessToken = jwtService.generateAccessToken(username, role);
        String refreshToken = jwtService.generateRefreshToken(username);

        System.out.println("[Auth] Registered user: " + username + " (role: " + role +
                ") on server: " + serverId);

        return ResponseEntity.ok(tokenResponse(username, role, accessToken, refreshToken));
    }

    /**
     * Login with credentials.
     *
     * POST /api/auth/login
     * Body: {"username": "alice", "password": "password123"}
     *
     * Note: This is the ONLY endpoint that hits the database for auth.
     * All subsequent requests use the JWT token — no database lookup.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        if (username == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "username and password are required"));
        }

        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "invalid credentials"));
        }

        String accessToken = jwtService.generateAccessToken(username, user.getRole());
        String refreshToken = jwtService.generateRefreshToken(username);

        System.out.println("[Auth] Login: " + username + " on server: " + serverId);

        return ResponseEntity.ok(tokenResponse(username, user.getRole(), accessToken, refreshToken));
    }

    /**
     * Get a new access token using a refresh token.
     *
     * POST /api/auth/refresh
     * Body: {"refreshToken": "eyJhbGciOi..."}
     *
     * This is why refresh tokens exist: the access token expires every
     * 15 minutes, but the user shouldn't have to log in again. The client
     * sends the refresh token (valid for 24 hours) and gets a fresh
     * access token.
     *
     * IMPORTANT: We look up the user in the database here. Why?
     * The user's role might have changed since the refresh token was
     * issued (e.g., promoted to ADMIN). The new access token should
     * reflect the current role, not the old one.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refreshToken");

        if (refreshToken == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "refreshToken is required"));
        }

        Claims claims = jwtService.validateToken(refreshToken);
        if (claims == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "invalid or expired refresh token"));
        }

        // Verify it's a refresh token (not an access token being reused)
        String tokenType = claims.get("type", String.class);
        if (!"refresh".equals(tokenType)) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "token is not a refresh token"));
        }

        String username = claims.getSubject();

        // Look up user to get current role
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "user no longer exists"));
        }

        String newAccessToken = jwtService.generateAccessToken(username, user.getRole());

        System.out.println("[Auth] Token refresh for: " + username + " on server: " + serverId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accessToken", newAccessToken);
        response.put("expiresIn", jwtService.getAccessTokenExpirationMs() / 1000);
        response.put("serverId", serverId);

        return ResponseEntity.ok(response);
    }

    /**
     * Logout — blacklist the current access token. (Part C)
     *
     * POST /api/auth/logout
     * Header: Authorization: Bearer <access-token>
     *
     * WHY BLACKLISTING IS NEEDED:
     * JWTs are stateless — once issued, they're valid until expiration.
     * You can't "revoke" a JWT the way you can delete a session.
     * The only way to invalidate a JWT before expiration is to maintain
     * a blacklist and check it on every request.
     *
     * This is the ONE place where JWTs need shared state (Redis).
     * But the blacklist is much smaller than a session store — only
     * recently-revoked tokens are in it, and they auto-expire.
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader("Authorization") String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            jwtService.blacklistToken(token);

            String username = jwtService.extractUsername(token);
            System.out.println("[Auth] Logout: " + username + " on server: " + serverId);
        }

        return ResponseEntity.ok(Map.of(
                "message", "logged out",
                "serverId", serverId));
    }

    /**
     * Verify a token is valid (utility endpoint for debugging).
     *
     * GET /api/auth/verify
     * Header: Authorization: Bearer <access-token>
     */
    @GetMapping("/verify")
    public ResponseEntity<?> verify(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body(Map.of(
                    "valid", false,
                    "error", "no token provided"));
        }

        String token = authHeader.substring(7);
        Claims claims = jwtService.validateToken(token);

        if (claims == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "valid", false,
                    "error", "invalid or expired token"));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", true);
        response.put("username", claims.getSubject());
        response.put("role", claims.get("role"));
        response.put("type", claims.get("type"));
        response.put("issuedAt", claims.getIssuedAt());
        response.put("expiresAt", claims.getExpiration());
        response.put("tokenId", claims.getId());
        response.put("serverId", serverId);

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> tokenResponse(String username, String role,
                                               String accessToken, String refreshToken) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("username", username);
        response.put("role", role);
        response.put("accessToken", accessToken);
        response.put("refreshToken", refreshToken);
        response.put("expiresIn", jwtService.getAccessTokenExpirationMs() / 1000);
        response.put("serverId", serverId);
        return response;
    }
}
