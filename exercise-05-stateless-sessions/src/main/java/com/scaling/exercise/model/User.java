package com.scaling.exercise.model;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * User entity for JWT authentication.
 *
 * NEW IN EXERCISE 05: We need users to demonstrate authentication.
 * In previous exercises, the API was open — anyone could call any endpoint.
 * Now some endpoints require a valid JWT token, which you get by logging in.
 *
 * We store a hashed password (BCrypt) — never plain text.
 * The role field enables role-based access control in Part C.
 */
@Entity
@Table(name = "users")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password; // BCrypt hash

    @Column(nullable = false)
    private String role; // USER or ADMIN

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public User() {}

    public User(String username, String password, String role) {
        this.username = username;
        this.password = password;
        this.role = role;
        this.createdAt = LocalDateTime.now();
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
