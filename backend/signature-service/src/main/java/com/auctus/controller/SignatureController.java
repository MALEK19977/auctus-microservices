package com.auctus.controller;

import com.auctus.service.PythonExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/signature")
@RequiredArgsConstructor
@Slf4j
public class SignatureController {

    private final PythonExecutorService pythonExecutorService;

    /**
     * Verifies the signature on a cheque against the one enrolled for the account
     * holder.
     *
     * <p>Identify the holder by {@code rib} whenever possible: names are not unique
     * (several clients can be called YOUSSEF GHARBI, each with their own signature),
     * so matching by name can pull up the wrong specimen and reject a valid cheque.
     */
    @PostMapping("/verify-by-titulaire")
    public ResponseEntity<Map<String, Object>> verifySignatureByTitulaire(
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "titulaire", required = false) String titulaire,
            @RequestParam(value = "rib", required = false) String rib) {

        String identifier = (rib != null && !rib.isBlank()) ? rib : titulaire;

        log.info("Signature verification: image={} ({} bytes), holder={}",
                image.getOriginalFilename(), image.getSize(), identifier);

        if (identifier == null || identifier.isBlank()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Provide the account holder's RIB (preferred) or name");
            return ResponseEntity.badRequest().body(error);
        }

        try {
            Map<String, Object> result = pythonExecutorService.verifySignatureByTitulaire(
                image.getBytes(),
                image.getOriginalFilename(),
                identifier
            );
            
            log.info("Result: {}", result);
            return ResponseEntity.ok(result);
            
        } catch (IOException e) {
            log.error("Error reading image: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Error reading image: " + e.getMessage());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            log.error("Error processing signature: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Error processing signature: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
    
    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("Signature Service is running!");
    }
}