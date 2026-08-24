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
@RequestMapping("/api/ocr")
@RequiredArgsConstructor
public class OcrController {

    private final PythonExecutorService pythonExecutorService;

    @PostMapping("/detect")
    public ResponseEntity<Map<String, Object>> detectCheque(@RequestParam("image") MultipartFile image) {
        try {
            Map<String, Object> result = pythonExecutorService.detectCheque(image.getBytes(), image.getOriginalFilename());
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("is_cheque", false);
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }
    
    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("OCR Service is running!");
    }
}