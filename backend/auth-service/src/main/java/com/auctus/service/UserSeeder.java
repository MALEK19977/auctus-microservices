package com.auctus.service;

import com.auctus.entity.User;
import com.auctus.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Creates the starter accounts so a fresh database is usable.
 *
 * <p>Only fills in what is missing: an existing account is never overwritten, so a
 * changed password survives a restart.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public void run(ApplicationArguments args) {
        seed("admin@auctus.com", "Admin@2025", "ADMIN", "Sami", "Ben Youssef");
        seed("agent@auctus.com", "Agent@2025", "AGENT", "Nadia", "Trabelsi");
        log.info("Accounts available: {}", userRepository.count());
    }

    private void seed(String email, String rawPassword, String role, String firstName, String lastName) {
        if (userRepository.findByEmail(email).isPresent()) {
            log.info("Account {} already exists, left untouched", email);
            return;
        }

        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setEmail(email);
        user.setPassword(encoder.encode(rawPassword));
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setRole(role);
        user.setStatus("ACTIVE");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        log.info("Created {} account: {} (password set from the seeder)", role, email);
    }
}
