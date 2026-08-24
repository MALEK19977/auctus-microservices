package com.auctus.service;

import com.auctus.entity.User;
import com.auctus.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public User authenticate(String email, String password) {
        if (email == null || password == null) {
            return null;
        }

        User user = userRepository.findByEmail(email.trim().toLowerCase()).orElse(null);
        if (user == null) {
            log.info("Login refused: no account for {}", email);
            return null;
        }

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            log.info("Login refused: account {} is {}", email, user.getStatus());
            return null;
        }

        String stored = user.getPassword();
        if (stored == null || stored.isBlank()) {
            return null;
        }

        if (isHashed(stored)) {
            if (!encoder.matches(password, stored)) {
                log.info("Login refused: wrong password for {}", email);
                return null;
            }
        } else {
            // Accounts created before hashing was introduced still hold their password
            // in clear. Accept it once, then immediately replace it with a hash so the
            // clear-text value stops existing.
            if (!stored.equals(password)) {
                log.info("Login refused: wrong password for {}", email);
                return null;
            }
            user.setPassword(encoder.encode(password));
            log.info("Upgraded stored password to a hash for {}", email);
        }

        user.setLastLogin(LocalDateTime.now());
        return userRepository.save(user);
    }

    private static boolean isHashed(String value) {
        return value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$");
    }
}
