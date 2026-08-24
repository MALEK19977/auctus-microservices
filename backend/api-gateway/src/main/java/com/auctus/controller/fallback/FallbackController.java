package com.auctus.controller.fallback;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/fallback")
public class FallbackController {
    
    @GetMapping("/auth")
    public Map<String, String> authFallback() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "DOWN");
        response.put("message", "Le service d'authentification est indisponible. Veuillez réessayer plus tard.");
        response.put("code", "SVC_001");
        return response;
    }
    
    @GetMapping("/cheque")
    public Map<String, String> chequeFallback() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "DOWN");
        response.put("message", "Le service de validation des cheques est indisponible.");
        response.put("code", "SVC_002");
        return response;
    }
    
    @GetMapping("/ocr")
    public Map<String, String> ocrFallback() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "DOWN");
        response.put("message", "Le service OCR est indisponible.");
        response.put("code", "SVC_003");
        return response;
    }
    
    @GetMapping("/qr")
    public Map<String, String> qrFallback() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "DOWN");
        response.put("message", "Le service QR code est indisponible.");
        response.put("code", "SVC_004");
        return response;
    }
    
    @GetMapping("/signature")
    public Map<String, String> signatureFallback() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "DOWN");
        response.put("message", "Le service de verification de signature est indisponible.");
        response.put("code", "SVC_005");
        return response;
    }

    @GetMapping("/collab")
    public Map<String, String> collabFallback() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "DOWN");
        response.put("message", "Le service de messagerie et d'agenda est indisponible.");
        response.put("code", "SVC_007");
        return response;
    }

    @GetMapping("/client")
    public Map<String, String> clientFallback() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "DOWN");
        response.put("message", "Le service client est indisponible.");
        response.put("code", "SVC_006");
        return response;
    }
}