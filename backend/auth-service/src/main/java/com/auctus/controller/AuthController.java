package com.auctus.controller;

import com.auctus.dto.LoginRequest;
import com.auctus.dto.LoginResponse;
import com.auctus.entity.User;
import com.auctus.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = "http://localhost:4200")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        User user = authService.authenticate(request.getEmail(), request.getPassword());
        
        if (user == null) {
            return ResponseEntity.status(401).body("Email ou mot de passe incorrect");
        }
        
        LoginResponse response = LoginResponse.builder()
                .token("dummy-token-" + System.currentTimeMillis())
                .id(user.getId())
                .userId(user.getId())
                .email(user.getEmail())
                .role(user.getRole())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .status(user.getStatus())
                .build();
        
        return ResponseEntity.ok(response);
    }
}