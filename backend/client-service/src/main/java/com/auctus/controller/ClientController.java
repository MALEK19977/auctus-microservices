package com.auctus.controller;

import com.auctus.entity.Client;
import com.auctus.service.ClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202"})
@RestController
@RequestMapping("/api/client")
@RequiredArgsConstructor
@Slf4j
public class ClientController {

    private final ClientService clientService;

    /** One search box: RIB, account number, CIN or name. */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam("q") String term) {
        List<Client> results = clientService.search(term);
        log.info("Client search '{}' -> {} result(s)", term, results.size());
        return ResponseEntity.ok(Map.of(
                "query", term,
                "count", results.size(),
                "results", results));
    }

    @GetMapping("/rib/{rib}")
    public ResponseEntity<?> byRib(@PathVariable String rib) {
        return clientService.findByRib(rib)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "Aucun compte ne correspond à ce RIB", "rib", rib)));
    }

    @GetMapping("/account/{accountNumber}")
    public ResponseEntity<?> byAccount(@PathVariable String accountNumber) {
        return clientService.findByAccountNumber(accountNumber)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "Compte introuvable", "accountNumber", accountNumber)));
    }

    @GetMapping("/{clientId}")
    public ResponseEntity<?> byId(@PathVariable String clientId) {
        return clientService.findById(clientId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "Client introuvable", "clientId", clientId)));
    }

    /** Does the issuing account hold enough money to honour this cheque? */
    @GetMapping("/funds")
    public ResponseEntity<Map<String, Object>> checkFunds(
            @RequestParam("rib") String rib,
            @RequestParam(value = "amount", required = false) BigDecimal amount) {
        return ResponseEntity.ok(clientService.checkFunds(rib, amount));
    }

    /**
     * The client's enrolled signature specimen, so an agent can compare it with the
     * cheque by eye when automatic matching is inconclusive.
     */
    @GetMapping("/{clientId}/signature")
    public ResponseEntity<byte[]> signatureSpecimen(@PathVariable String clientId) {
        return clientService.readSignatureSpecimen(clientId)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("Client Service is running!");
    }
}
