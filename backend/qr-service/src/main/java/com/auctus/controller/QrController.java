package com.auctus.controller;

import com.auctus.service.PythonExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/qr")
@RequiredArgsConstructor
public class QrController {

    private final PythonExecutorService pythonExecutorService;

    @PostMapping("/read")
    public ResponseEntity<Map<String, Object>> readQrCode(@RequestParam("image") MultipartFile image) {
        try {
            Map<String, Object> result = pythonExecutorService.readQrCode(image.getBytes(), image.getOriginalFilename());
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }
    
    /**
     * Cross-checks the QR code against the fields printed on the cheque and applies
     * the plafond / expiry rules of the 2025 Tunisian cheque reform.
     */
    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyCheque(@RequestParam("image") MultipartFile image) {
        try {
            Map<String, Object> result = pythonExecutorService.verifyCheque(
                    image.getBytes(), image.getOriginalFilename());
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("QR Service is running!");
    }
}