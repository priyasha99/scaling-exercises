package com.scaling.exercise.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JWT token service — the core of stateless authentication.
 *
 * KEY CONCEPT:
 * A JWT (JSON Web Token) is a self-contained, signed token. It contains
 * the user's identity (username, role) and an expiration time, all signed
 * with a secret key. Any server that knows the secret can verify the token
 * without calling a database or session store.
 *
 * WHY THIS ENABLES HORIZONTAL SCALING:
 *
 *   With server-side sessions (HttpSession):
 *     User logs in on Server 1 → session stored in Server 1's memory
 *     Next request goes to Server 2 → Server 2 has no session → 401
 *     "Fix": sticky sessions (pin user to one server) → kills load balancing
 *
 *   With JWT:
 *     User logs in on Server 1 → gets a signed token
 *     Next request goes to Server 2 → Server 2 verifies the signature → works!
 *     No sticky sessions needed. Any server handles any request.
 *
 * TOKEN STRUCTURE:
 *   Header:  {"alg": "HS256", "typ": "JWT"}
 *   Payload: {"sub": "alice", "role": "ADMIN", "iat": 1234567890, "exp": 1234568790}
 *   Signature: HMACSHA256(base64(header) + "." + base64(payload), secret)
 *
 * The token is sent as: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWI...
 *
 * PART C ADDITIONS:
 * - Refresh tokens: long-lived tokens used to get new access tokens
 * - Token blacklist: Redis set of revoked token IDs (for logout)
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;
    private final StringRedisTemplate redisTemplate;

    // Redis key prefix for blacklisted tokens
    private static final String BLACKLIST_PREFIX = "jwt_blacklist::";

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration-ms}") long accessTokenExpirationMs,
            @Value("${jwt.refresh-token-expiration-ms}") long refreshTokenExpirationMs,
            StringRedisTemplate redisTemplate) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationMs = accessTokenExpirationMs;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Generate a short-lived access token (15 minutes by default).
     * Contains: username (sub), role, token type, unique ID (jti).
     *
     * The jti (JWT ID) is used for blacklisting — when a user logs out,
     * we add their token's jti to the Redis blacklist.
     */
    public String generateAccessToken(String username, String role) {
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .claim("type", "access")
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpirationMs))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Generate a long-lived refresh token (24 hours by default).
     * Used to get a new access token without re-entering credentials.
     *
     * Refresh tokens have minimal claims — they're only used to prove
     * identity when requesting a new access token, not for API access.
     */
    public String generateRefreshToken(String username) {
        return Jwts.builder()
                .subject(username)
                .claim("type", "refresh")
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshTokenExpirationMs))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Validate a token and return its claims.
     *
     * Verification checks:
     * 1. Signature — was this token signed with our secret? (tamper detection)
     * 2. Expiration — is the token still valid? (auto-expiry)
     * 3. Blacklist — has this token been explicitly revoked? (logout support)
     *
     * Any failure returns null (invalid/expired/revoked token).
     */
    public Claims validateToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // Check the blacklist (Part C)
            if (isBlacklisted(claims.getId())) {
                System.out.println("[JWT] Token " + claims.getId() + " is blacklisted");
                return null;
            }

            return claims;
        } catch (ExpiredJwtException e) {
            System.out.println("[JWT] Token expired for: " + e.getClaims().getSubject());
            return null;
        } catch (JwtException e) {
            System.out.println("[JWT] Invalid token: " + e.getMessage());
            return null;
        }
    }

    /**
     * Extract username from token without full validation.
     * Used when you need the username even from an expired token
     * (e.g., for logging).
     */
    public String extractUsername(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
        } catch (JwtException e) {
            return null;
        }
    }

    // =========================================================
    // Part C: Token Blacklist (for logout/revocation)
    // =========================================================

    /**
     * Blacklist a token by its JTI (JWT ID).
     *
     * WHY USE REDIS FOR THE BLACKLIST?
     * The blacklist must be shared across all app servers. If Server 1
     * handles the logout, Server 2 must also reject that token.
     * Redis is already running (for caching), so we reuse it.
     *
     * The blacklist entry has a TTL matching the token's remaining lifetime.
     * Once the token would have expired anyway, the blacklist entry is
     * automatically cleaned up — no stale data.
     */
    public void blacklistToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String jti = claims.getId();
            if (jti != null) {
                // TTL = time until this token would have expired
                long ttlMs = claims.getExpiration().getTime() - System.currentTimeMillis();
                if (ttlMs > 0) {
                    redisTemplate.opsForValue().set(
                            BLACKLIST_PREFIX + jti,
                            "revoked",
                            ttlMs,
                            TimeUnit.MILLISECONDS
                    );
                    System.out.println("[JWT] Blacklisted token " + jti +
                            " (TTL: " + (ttlMs / 1000) + "s)");
                }
            }
        } catch (JwtException e) {
            // Token is already invalid/expired — nothing to blacklist
        }
    }

    /**
     * Check if a token ID is in the blacklist.
     */
    private boolean isBlacklisted(String jti) {
        if (jti == null) return false;
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti));
    }

    public long getAccessTokenExpirationMs() {
        return accessTokenExpirationMs;
    }

    public long getRefreshTokenExpirationMs() {
        return refreshTokenExpirationMs;
    }
}
