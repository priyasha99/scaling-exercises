package com.scaling.exercise.controller;

import com.scaling.exercise.model.User;
import com.scaling.exercise.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Admin-only endpoints — requires ADMIN role in JWT.
 *
 * Part C: Role-based access control (RBAC).
 *
 * The JWT token contains the user's role. Spring Security checks
 * the role before allowing access to these endpoints. A USER token
 * gets 403 Forbidden. An ADMIN token gets through.
 *
 * All of this happens WITHOUT a database lookup — the role is in the token.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserRepository userRepository;
    private final String serverId;

    public AdminController(UserRepository userRepository) {
        this.userRepository = userRepository;
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        this.serverId = hostname;
    }

    /**
     * List all users (admin only).
     * Passwords are not returned.
     */
    @GetMapping("/users")
    public ResponseEntity<?> listUsers(Authentication authentication) {
        List<Map<String, Object>> users = userRepository.findAll().stream()
                .map(user -> {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("id", user.getId());
                    u.put("username", user.getUsername());
                    u.put("role", user.getRole());
                    u.put("createdAt", user.getCreatedAt());
                    return u;
                })
                .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestedBy", authentication.getName());
        response.put("serverId", serverId);
        response.put("userCount", users.size());
        response.put("users", users);

        return ResponseEntity.ok(response);
    }

    /**
     * Admin dashboard — shows system info (admin only).
     */
    @GetMapping("/dashboard")
    public ResponseEntity<?> dashboard(Authentication authentication) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestedBy", authentication.getName());
        response.put("serverId", serverId);
        response.put("totalUsers", userRepository.count());
        response.put("jvmFreeMemory", Runtime.getRuntime().freeMemory());
        response.put("jvmTotalMemory", Runtime.getRuntime().totalMemory());
        response.put("availableProcessors", Runtime.getRuntime().availableProcessors());

        return ResponseEntity.ok(response);
    }
}
