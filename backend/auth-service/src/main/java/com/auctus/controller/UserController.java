package com.auctus.controller;

import com.auctus.entity.User;
import com.auctus.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent management for the admin console.
 *
 * <p>Password hashes are never returned by any endpoint here.
 */
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202"})
@RestController
@RequestMapping("/api/auth/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "role", required = false) String role) {

        List<Map<String, Object>> users = userRepository.findAll().stream()
                .filter(u -> role == null || role.isBlank() || role.equalsIgnoreCase(u.getRole()))
                .sorted(Comparator.comparing(User::getEmail))
                .map(UserController::publicView)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("count", users.size(), "users", users));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        String email = value(body, "email");
        String password = value(body, "password");

        if (email == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "email et password sont obligatoires"));
        }
        if (password.length() < 10) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Le mot de passe doit faire au moins 10 caractères"));
        }
        if (userRepository.findByEmail(email.toLowerCase()).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "Un compte existe déjà pour " + email));
        }

        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setEmail(email.toLowerCase());
        user.setPassword(encoder.encode(password));
        user.setFirstName(value(body, "firstName"));
        user.setLastName(value(body, "lastName"));
        user.setRole(Optional.ofNullable(value(body, "role")).orElse("AGENT").toUpperCase());
        user.setStatus("ACTIVE");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        log.info("Created {} account {}", user.getRole(), user.getEmail());
        return ResponseEntity.ok(publicView(user));
    }

    /** Update the profile, role or status of an account. */
    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, String> body) {
        return userRepository.findById(id).<ResponseEntity<?>>map(user -> {
            Optional.ofNullable(value(body, "firstName")).ifPresent(user::setFirstName);
            Optional.ofNullable(value(body, "lastName")).ifPresent(user::setLastName);
            Optional.ofNullable(value(body, "role")).map(String::toUpperCase).ifPresent(user::setRole);
            Optional.ofNullable(value(body, "status")).map(String::toUpperCase).ifPresent(user::setStatus);

            String newPassword = value(body, "password");
            if (newPassword != null) {
                if (newPassword.length() < 10) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Le mot de passe doit faire au moins 10 caractères"));
                }
                user.setPassword(encoder.encode(newPassword));
                log.info("Password reset for {}", user.getEmail());
            }

            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);
            return ResponseEntity.ok(publicView(user));
        }).orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Compte introuvable")));
    }

    /** Accounts are deactivated, never deleted - their validations must stay attributable. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deactivate(@PathVariable String id) {
        return userRepository.findById(id).<ResponseEntity<?>>map(user -> {
            user.setStatus("INACTIVE");
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);
            log.info("Deactivated account {}", user.getEmail());
            return ResponseEntity.ok(publicView(user));
        }).orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Compte introuvable")));
    }

    private static Map<String, Object> publicView(User user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", user.getId());
        view.put("email", user.getEmail());
        view.put("firstName", user.getFirstName());
        view.put("lastName", user.getLastName());
        view.put("role", user.getRole());
        view.put("status", user.getStatus());
        view.put("lastLogin", user.getLastLogin());
        view.put("createdAt", user.getCreatedAt());
        // Deliberately omits the password hash.
        return view;
    }

    private static String value(Map<String, String> body, String key) {
        String raw = body.get(key);
        return raw == null || raw.isBlank() ? null : raw.trim();
    }
}
