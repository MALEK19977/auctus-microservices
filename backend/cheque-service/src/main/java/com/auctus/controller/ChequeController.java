package com.auctus.controller;

import com.auctus.dto.ChequeValidationResponse;
import com.auctus.entity.Cheque;
import com.auctus.entity.ChequeStatus;
import com.auctus.repository.ChequeRepository;
import com.auctus.service.ChequeValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202"})
@RestController
@RequestMapping("/api/cheque")
@RequiredArgsConstructor
@Slf4j
public class ChequeController {

    private final ChequeValidationService chequeValidationService;
    private final ChequeRepository chequeRepository;

    @PostMapping("/validate")
    public ResponseEntity<ChequeValidationResponse> validateCheque(
            @RequestParam("frontImage") MultipartFile frontImage,
            @RequestParam("agentId") String agentId,
            @RequestParam(value = "agentName", required = false) String agentName,
            @RequestParam(value = "agentEmail", required = false) String agentEmail) {

        String display = (agentName != null && !agentName.isBlank()) ? agentName
                : (agentEmail != null && !agentEmail.isBlank()) ? agentEmail : agentId;

        log.info("Validation request: file={} agent={} ({})",
                frontImage.getOriginalFilename(), display, agentId);
        return ResponseEntity.ok(chequeValidationService.validateCheque(frontImage, agentId, display));
    }

    /**
     * Platform-wide counters plus the series the admin dashboard charts.
     * Scoped to one agent with {@code ?agentId=…}.
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics(
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "days", defaultValue = "14") int days,
            @RequestParam(value = "hours", required = false) Integer hours) {

        List<Cheque> cheques = scope(agentId);

        // An hour window answers "what happened this shift"; a day window answers
        // "how are we trending". Both narrow the same list before anything is counted.
        LocalDateTime cutoff = hours != null
                ? LocalDateTime.now().minusHours(hours)
                : LocalDate.now().minusDays(days - 1L).atStartOfDay();
        cheques = cheques.stream()
                .filter(c -> c.getValidatedAt() != null && !c.getValidatedAt().isBefore(cutoff))
                .collect(Collectors.toList());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", cheques.size());
        stats.put("accepted", count(cheques, ChequeStatus.ACCEPTED));
        stats.put("review", count(cheques, ChequeStatus.REVIEW));
        stats.put("rejected", count(cheques, ChequeStatus.REJECTED));

        BigDecimal covered = cheques.stream()
                .filter(c -> c.getStatus() == ChequeStatus.ACCEPTED)
                .map(c -> c.getPlafond() == null ? BigDecimal.ZERO : c.getPlafond())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        stats.put("totalPlafondAccepted", covered);

        stats.put("averageProcessingTime", cheques.stream()
                .filter(c -> c.getProcessingTime() != null)
                .mapToDouble(Cheque::getProcessingTime)
                .average().orElse(0.0));

        stats.put("averageSignatureScore", cheques.stream()
                .filter(c -> c.getSignatureScore() != null)
                .mapToDouble(Cheque::getSignatureScore)
                .average().orElse(0.0));

        LocalDate today = LocalDate.now();
        stats.put("today", cheques.stream()
                .filter(c -> c.getValidatedAt() != null && c.getValidatedAt().toLocalDate().equals(today))
                .count());

        stats.put("bucketUnit", hours != null ? "hour" : "day");
        stats.put("series", hours != null ? hourlySeries(cheques, hours) : dailySeries(cheques, days));
        stats.put("daily", stats.get("series"));   // kept for existing callers
        stats.put("byAgent", agentBreakdown(cheques));
        stats.put("rejectionReasons", rejectionBreakdown(cheques));
        stats.put("agents", knownAgents());

        return ResponseEntity.ok(stats);
    }

    /** Agents who have actually validated something - the admin's filter list. */
    private List<Map<String, Object>> knownAgents() {
        Map<String, String> names = new LinkedHashMap<>();
        for (Cheque cheque : chequeRepository.findAll()) {
            if (cheque.getValidatedBy() == null) {
                continue;
            }
            names.merge(cheque.getValidatedBy(), displayName(cheque), (a, b) -> a);
        }
        return names.entrySet().stream().map(e -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", e.getKey());
            row.put("name", e.getValue());
            return row;
        }).collect(Collectors.toList());
    }

    private static String displayName(Cheque cheque) {
        if (cheque.getValidatedByName() != null && !cheque.getValidatedByName().isBlank()) {
            return cheque.getValidatedByName();
        }
        // Older rows only stored an id; showing "Agent 4" at least reads as a person.
        String id = cheque.getValidatedBy();
        return id != null && id.matches("\\d+") ? "Agent " + id : id;
    }

    /** Persisted validation history - survives logout, cache clearing and machine changes. */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getHistory(
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        List<Cheque> all = scope(agentId);

        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            all = all.stream()
                    .filter(c -> c.getStatus() != null && c.getStatus().name().equalsIgnoreCase(status))
                    .collect(Collectors.toList());
        }

        int from = Math.max(0, page * size);
        int to = Math.min(all.size(), from + size);
        List<Cheque> pageItems = from >= all.size() ? List.of() : all.subList(from, to);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", pageItems);
        response.put("totalElements", all.size());
        response.put("totalPages", (int) Math.ceil(all.size() / (double) size));
        response.put("page", page);
        response.put("size", size);
        return ResponseEntity.ok(response);
    }

    /** Every cheque already validated against this account. */
    @GetMapping("/by-rib/{rib}")
    public ResponseEntity<Map<String, Object>> chequesByRib(@PathVariable String rib) {
        List<Cheque> cheques = chequeRepository.findByIssuerRibOrderByValidatedAtDesc(rib.replaceAll("\\s", ""));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rib", rib);
        response.put("count", cheques.size());
        response.put("cheques", cheques);
        return ResponseEntity.ok(response);
    }

    /**
     * Admin oversight: confirm or overturn what an agent decided. The original
     * verdict is preserved in the rejection reason so the trail is auditable.
     */
    @PostMapping("/{chequeId}/decision")
    public ResponseEntity<?> reviewDecision(@PathVariable String chequeId,
                                            @RequestBody Map<String, String> body) {
        String decision = body.getOrDefault("decision", "").toUpperCase();
        String reviewer = body.getOrDefault("reviewer", "admin");
        String note = body.getOrDefault("note", "");

        if (!List.of("ACCEPTED", "REJECTED", "REVIEW").contains(decision)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "decision must be ACCEPTED, REJECTED or REVIEW"));
        }

        return chequeRepository.findById(chequeId).<ResponseEntity<?>>map(cheque -> {
            ChequeStatus previous = cheque.getStatus();
            cheque.setStatus(ChequeStatus.valueOf(decision));
            cheque.setRejectionReason(String.format("[%s par %s le %s] %s (verdict initial : %s)",
                    decision, reviewer, LocalDateTime.now(), note, previous));
            cheque.setValidatedAt(LocalDateTime.now());
            chequeRepository.save(cheque);
            log.info("Cheque {} moved from {} to {} by {}", chequeId, previous, decision, reviewer);
            return ResponseEntity.ok(cheque);
        }).orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Chèque introuvable")));
    }

    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("Cheque Service is running!");
    }

    // ------------------------------------------------------------------ helpers

    private List<Cheque> scope(String agentId) {
        return (agentId == null || agentId.isBlank() || "ALL".equalsIgnoreCase(agentId))
                ? chequeRepository.findAll().stream()
                    .sorted(Comparator.comparing(Cheque::getValidatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .collect(Collectors.toList())
                : chequeRepository.findByValidatedByOrderByCreatedAtDesc(agentId);
    }

    private static long count(List<Cheque> cheques, ChequeStatus status) {
        return cheques.stream().filter(c -> c.getStatus() == status).count();
    }

    /** One bucket per day, oldest first, with zero-filled gaps so the chart is continuous. */
    private List<Map<String, Object>> dailySeries(List<Cheque> cheques, int days) {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> series = new ArrayList<>();
        for (int offset = days - 1; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            List<Cheque> onDay = cheques.stream()
                    .filter(c -> c.getValidatedAt() != null && c.getValidatedAt().toLocalDate().equals(day))
                    .collect(Collectors.toList());
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("date", day.toString());
            bucket.put("label", String.format("%02d/%02d", day.getDayOfMonth(), day.getMonthValue()));
            bucket.put("accepted", count(onDay, ChequeStatus.ACCEPTED));
            bucket.put("review", count(onDay, ChequeStatus.REVIEW));
            bucket.put("rejected", count(onDay, ChequeStatus.REJECTED));
            bucket.put("total", onDay.size());
            series.add(bucket);
        }
        return series;
    }

    /** One bucket per hour, oldest first - the view for "what happened this shift". */
    private List<Map<String, Object>> hourlySeries(List<Cheque> cheques, int hours) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        List<Map<String, Object>> series = new ArrayList<>();
        for (int offset = hours - 1; offset >= 0; offset--) {
            LocalDateTime slot = now.minusHours(offset);
            List<Cheque> inSlot = cheques.stream()
                    .filter(c -> c.getValidatedAt() != null
                            && !c.getValidatedAt().isBefore(slot)
                            && c.getValidatedAt().isBefore(slot.plusHours(1)))
                    .collect(Collectors.toList());
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("date", slot.toString());
            bucket.put("label", String.format("%02dh", slot.getHour()));
            bucket.put("accepted", count(inSlot, ChequeStatus.ACCEPTED));
            bucket.put("review", count(inSlot, ChequeStatus.REVIEW));
            bucket.put("rejected", count(inSlot, ChequeStatus.REJECTED));
            bucket.put("total", inSlot.size());
            series.add(bucket);
        }
        return series;
    }

    private List<Map<String, Object>> agentBreakdown(List<Cheque> cheques) {
        Map<String, List<Cheque>> byAgent = cheques.stream()
                .filter(c -> c.getValidatedBy() != null)
                .collect(Collectors.groupingBy(Cheque::getValidatedBy));

        return byAgent.entrySet().stream().map(entry -> {
            List<Cheque> items = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", entry.getKey());
            row.put("agentName", displayName(items.get(0)));
            row.put("total", items.size());
            row.put("accepted", count(items, ChequeStatus.ACCEPTED));
            row.put("review", count(items, ChequeStatus.REVIEW));
            row.put("rejected", count(items, ChequeStatus.REJECTED));
            row.put("averageProcessingTime", items.stream()
                    .filter(c -> c.getProcessingTime() != null)
                    .mapToDouble(Cheque::getProcessingTime).average().orElse(0.0));
            return row;
        }).sorted((a, b) -> Integer.compare((int) b.get("total"), (int) a.get("total")))
          .collect(Collectors.toList());
    }

    /** Why cheques get turned away - the signal an admin acts on. */
    private List<Map<String, Object>> rejectionBreakdown(List<Cheque> cheques) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Cheque cheque : cheques) {
            if (cheque.getStatus() != ChequeStatus.REJECTED) {
                continue;
            }
            counts.merge(classify(cheque.getRejectionReason()), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("reason", e.getKey());
                    row.put("count", e.getValue());
                    return row;
                })
                .sorted((a, b) -> Long.compare((long) b.get("count"), (long) a.get("count")))
                .collect(Collectors.toList());
    }

    /**
     * Turns a rejection message into a category an admin can act on. Every branch
     * names a concrete failure - there is no "Other" bucket, because "Other" tells
     * nobody what to fix.
     */
    private static String classify(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Reason not recorded (legacy record)";
        }
        String lower = reason.toLowerCase();
        if (lower.contains("signature")) return "Signature mismatch";
        if (lower.contains("plafond")) return "Ceiling altered on the cheque";
        if (lower.contains("numéro de chèque") || lower.contains("numero de cheque")) return "Cheque number altered";
        if (lower.contains("nom du titulaire")) return "Account holder name altered";
        if (lower.contains("rib")) return "Account number (RIB) altered";
        if (lower.contains("expir")) return "Cheque expired";
        if (lower.contains("qr")) return "QR code does not match the cheque";
        if (lower.contains("provision") || lower.contains("solde")) return "Insufficient funds";
        if (lower.contains("compte") && lower.contains("introuvable")) return "Account not found";
        if (lower.contains("pas un chèque") || lower.contains("n'est pas un")) return "Not a cheque image";
        if (lower.contains("revu depuis") || lower.startsWith("[rejected")) return "Overturned by an administrator";
        if (lower.contains("erreur de traitement")) return "Processing error";
        return "Unclassified rejection";
    }
}
