package com.auctus.controller;

import com.auctus.entity.*;
import com.auctus.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.*;

/**
 * The operations a branch agent actually carries out on a client file.
 *
 * <p>Five services are covered, chosen because they are what a task in the work
 * queue most often asks for:
 * <ol>
 *   <li>change the account type, including converting a minor's account when the
 *       holder comes of age;</li>
 *   <li>change the account status (activate, suspend, block);</li>
 *   <li>update contact details;</li>
 *   <li>open a credit file (consumer, housing or comfort savings);</li>
 *   <li>order a chequebook with its legal ceiling.</li>
 * </ol>
 *
 * <p>Every one writes an audit line before returning.
 */
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202"})
@RestController
@RequestMapping("/api/client")
@RequiredArgsConstructor
@Slf4j
public class ClientOperationsController {

    /** Ceiling imposed by the 2025 cheque reform. */
    private static final BigDecimal LEGAL_MAX_PLAFOND = new BigDecimal("30000");
    /** Age of majority in Tunisia. */
    private static final int ADULT_AGE = 18;
    /** Refused above this: the instalment would swallow too much of the income. */
    private static final BigDecimal MAX_DEBT_RATIO = new BigDecimal("40");

    private final ClientRepository clientRepository;
    private final ClientOperationRepository operationRepository;
    private final CreditApplicationRepository creditRepository;
    private final ChequeBookRequestRepository chequeBookRepository;

    // ------------------------------------------------------------ 1. account type

    /**
     * Changes the account type. A minor's account may only become an ordinary one
     * once the holder has actually reached majority - that is the check an agent
     * would otherwise have to remember.
     */
    @PatchMapping("/{clientId}/account-type")
    @Transactional
    public ResponseEntity<?> changeAccountType(@PathVariable String clientId,
                                               @RequestBody Map<String, Object> body) {
        Client client = clientRepository.findById(clientId).orElse(null);
        if (client == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Client not found"));
        }

        String newType = upper(body, "accountType");
        if (newType == null || !List.of("COURANT", "EPARGNE", "PROFESSIONNEL", "JEUNE").contains(newType)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "accountType must be COURANT, EPARGNE, PROFESSIONNEL or JEUNE"));
        }

        boolean leavingMinor = Boolean.TRUE.equals(client.getMinor()) && !"JEUNE".equals(newType);
        int age = ageOf(client);
        if (leavingMinor && age < ADULT_AGE) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "The holder is " + age + " and cannot hold this account type yet",
                    "requiredAge", ADULT_AGE));
        }

        String previous = client.getAccountType();
        client.setAccountType(newType);
        if (leavingMinor) {
            // Coming of age: the guardian is no longer party to the account.
            client.setMinor(false);
            client.setGuardianName(null);
            client.setGuardianCin(null);
        }
        client.setUpdatedAt(LocalDateTime.now());
        clientRepository.save(client);

        audit(client, leavingMinor ? "MINOR_TO_ADULT" : "ACCOUNT_TYPE_CHANGE",
                previous, newType, body);

        log.info("Client {} account type {} -> {}", clientId, previous, newType);
        return ResponseEntity.ok(Map.of("client", client, "convertedFromMinor", leavingMinor));
    }

    // ---------------------------------------------------------- 2. account status

    @PatchMapping("/{clientId}/status")
    @Transactional
    public ResponseEntity<?> changeStatus(@PathVariable String clientId,
                                          @RequestBody Map<String, Object> body) {
        Client client = clientRepository.findById(clientId).orElse(null);
        if (client == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Client not found"));
        }

        String status = upper(body, "status");
        if (status == null || !List.of("ACTIVE", "SUSPENDED", "BLOCKED", "CLOSED").contains(status)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "status must be ACTIVE, SUSPENDED, BLOCKED or CLOSED"));
        }
        String reason = text(body, "reason");
        if (!"ACTIVE".equals(status) && reason == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "A reason is required to suspend, block or close an account"));
        }
        if ("CLOSED".equals(status) && client.getBalance() != null
                && client.getBalance().compareTo(BigDecimal.ZERO) > 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "The account still holds " + client.getBalance() + " DT and cannot be closed"));
        }

        String previous = client.getStatus();
        client.setStatus(status);
        client.setUpdatedAt(LocalDateTime.now());
        clientRepository.save(client);

        audit(client, "ACCOUNT_STATUS_CHANGE", previous, status, body);
        return ResponseEntity.ok(client);
    }

    // --------------------------------------------------------- 3. contact details

    @PatchMapping("/{clientId}/contact")
    @Transactional
    public ResponseEntity<?> updateContact(@PathVariable String clientId,
                                           @RequestBody Map<String, Object> body) {
        Client client = clientRepository.findById(clientId).orElse(null);
        if (client == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Client not found"));
        }

        String before = String.format("%s / %s / %s, %s",
                client.getPhone(), client.getEmail(), client.getAddress(), client.getCity());

        Optional.ofNullable(text(body, "phone")).ifPresent(client::setPhone);
        Optional.ofNullable(text(body, "email")).ifPresent(client::setEmail);
        Optional.ofNullable(text(body, "address")).ifPresent(client::setAddress);
        Optional.ofNullable(text(body, "city")).ifPresent(client::setCity);
        client.setUpdatedAt(LocalDateTime.now());
        clientRepository.save(client);

        String after = String.format("%s / %s / %s, %s",
                client.getPhone(), client.getEmail(), client.getAddress(), client.getCity());
        audit(client, "CONTACT_UPDATE", before, after, body);
        return ResponseEntity.ok(client);
    }

    // ------------------------------------------------------------- 4. credit file

    /** Opens a credit file and works out the debt ratio the committee will look at. */
    @PostMapping("/{clientId}/credits")
    @Transactional
    public ResponseEntity<?> applyForCredit(@PathVariable String clientId,
                                            @RequestBody Map<String, Object> body) {
        Client client = clientRepository.findById(clientId).orElse(null);
        if (client == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Client not found"));
        }
        if (Boolean.TRUE.equals(client.getMinor())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "A minor's account cannot take on credit"));
        }

        String type = upper(body, "type");
        if (type == null || !List.of("CONSUMER", "HOUSING", "COMFORT_SAVINGS").contains(type)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "type must be CONSUMER, HOUSING or COMFORT_SAVINGS"));
        }

        BigDecimal amount = decimal(body, "amount");
        Integer months = integer(body, "durationMonths");
        BigDecimal income = decimal(body, "monthlyIncome");
        if (amount == null || months == null || months <= 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "amount and durationMonths are required"));
        }

        // Product ceilings, as offered by the branch.
        if ("CONSUMER".equals(type) && amount.compareTo(new BigDecimal("30000")) > 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Consumer credit is capped at 30 000 DT"));
        }
        if ("COMFORT_SAVINGS".equals(type) && client.getBalance() != null
                && amount.compareTo(client.getBalance().multiply(new BigDecimal("5"))) > 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "A comfort savings loan cannot exceed five times the savings ("
                            + client.getBalance().multiply(new BigDecimal("5")) + " DT)"));
        }

        BigDecimal instalment = amount.divide(new BigDecimal(months), 3, RoundingMode.HALF_UP);
        BigDecimal ratio = (income == null || income.compareTo(BigDecimal.ZERO) == 0)
                ? null
                : instalment.multiply(new BigDecimal("100")).divide(income, 2, RoundingMode.HALF_UP);

        CreditApplication application = new CreditApplication();
        application.setId(UUID.randomUUID().toString());
        application.setClientId(clientId);
        application.setClientRib(client.getRib());
        application.setClientName(client.getFullName());
        application.setType(type);
        application.setAmount(amount);
        application.setDurationMonths(months);
        application.setMonthlyIncome(income);
        application.setMonthlyInstalment(instalment);
        application.setDebtRatio(ratio);
        application.setPurpose(text(body, "purpose"));
        application.setDocumentsProvided(text(body, "documentsProvided"));
        application.setStatus("SUBMITTED");
        application.setTaskId(text(body, "taskId"));
        application.setCreatedById(text(body, "performedById"));
        application.setCreatedByName(text(body, "performedByName"));
        application.setCreatedAt(LocalDateTime.now());
        creditRepository.save(application);

        audit(client, "CREDIT_APPLICATION", null,
                type + " " + amount + " DT over " + months + " months", body);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("application", application);
        response.put("debtRatio", ratio);
        response.put("withinPolicy", ratio == null || ratio.compareTo(MAX_DEBT_RATIO) <= 0);
        response.put("note", ratio == null
                ? "Declared income missing - the committee will need it"
                : ratio.compareTo(MAX_DEBT_RATIO) > 0
                    ? "Debt ratio " + ratio + "% exceeds the 40% policy limit"
                    : "Debt ratio " + ratio + "% is within policy");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{clientId}/credits")
    public ResponseEntity<Map<String, Object>> credits(@PathVariable String clientId) {
        List<CreditApplication> items = creditRepository.findByClientIdOrderByCreatedAtDesc(clientId);
        return ResponseEntity.ok(Map.of("count", items.size(), "credits", items));
    }

    /** An administrator's decision on a credit file. */
    @PatchMapping("/credits/{creditId}")
    @Transactional
    public ResponseEntity<?> decideCredit(@PathVariable String creditId,
                                          @RequestBody Map<String, Object> body) {
        CreditApplication application = creditRepository.findById(creditId).orElse(null);
        if (application == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Credit file not found"));
        }
        String decision = upper(body, "status");
        if (decision == null || !List.of("APPROVED", "REFUSED", "SUBMITTED").contains(decision)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "status must be APPROVED, REFUSED or SUBMITTED"));
        }
        application.setStatus(decision);
        application.setDecisionNote(text(body, "decisionNote"));
        application.setDecidedById(text(body, "performedById"));
        application.setDecidedByName(text(body, "performedByName"));
        application.setDecidedAt(LocalDateTime.now());
        creditRepository.save(application);
        return ResponseEntity.ok(application);
    }

    // ---------------------------------------------------------- 5. chequebook

    @PostMapping("/{clientId}/chequebooks")
    @Transactional
    public ResponseEntity<?> requestChequeBook(@PathVariable String clientId,
                                               @RequestBody Map<String, Object> body) {
        Client client = clientRepository.findById(clientId).orElse(null);
        if (client == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Client not found"));
        }
        if (!"ACTIVE".equalsIgnoreCase(client.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "A chequebook cannot be issued on a " + client.getStatus() + " account"));
        }
        if (Boolean.TRUE.equals(client.getMinor())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "A minor's account cannot be issued a chequebook"));
        }

        BigDecimal plafond = decimal(body, "plafond");
        if (plafond == null || plafond.compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "A ceiling is required"));
        }
        if (plafond.compareTo(LEGAL_MAX_PLAFOND) > 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "The ceiling may not exceed 30 000 DT under the 2025 reform"));
        }

        ChequeBookRequest request = new ChequeBookRequest();
        request.setId(UUID.randomUUID().toString());
        request.setClientId(clientId);
        request.setClientRib(client.getRib());
        request.setClientName(client.getFullName());
        request.setLeafCount(Optional.ofNullable(integer(body, "leafCount")).orElse(25));
        request.setPlafond(plafond);
        request.setStatus("REQUESTED");
        request.setNote(text(body, "note"));
        request.setTaskId(text(body, "taskId"));
        request.setRequestedById(text(body, "performedById"));
        request.setRequestedByName(text(body, "performedByName"));
        request.setRequestedAt(LocalDateTime.now());
        request.setUpdatedAt(LocalDateTime.now());
        chequeBookRepository.save(request);

        audit(client, "CHEQUEBOOK_REQUEST", null,
                request.getLeafCount() + " leaves, ceiling " + plafond + " DT", body);
        return ResponseEntity.ok(request);
    }

    @GetMapping("/{clientId}/chequebooks")
    public ResponseEntity<Map<String, Object>> chequeBooks(@PathVariable String clientId) {
        List<ChequeBookRequest> items = chequeBookRepository.findByClientIdOrderByRequestedAtDesc(clientId);
        return ResponseEntity.ok(Map.of("count", items.size(), "chequebooks", items));
    }

    // ------------------------------------------------------------------- history

    /** Everything ever done to this file, newest first. */
    @GetMapping("/{clientId}/operations")
    public ResponseEntity<Map<String, Object>> operations(@PathVariable String clientId) {
        List<ClientOperation> items = operationRepository.findByClientIdOrderByPerformedAtDesc(clientId);
        return ResponseEntity.ok(Map.of("count", items.size(), "operations", items));
    }

    // ------------------------------------------------------------------- helpers

    private void audit(Client client, String operation, String previous, String next,
                       Map<String, Object> body) {
        ClientOperation entry = new ClientOperation();
        entry.setId(UUID.randomUUID().toString());
        entry.setClientId(client.getClientId());
        entry.setClientRib(client.getRib());
        entry.setOperation(operation);
        entry.setPreviousValue(previous);
        entry.setNewValue(next);
        entry.setReason(text(body, "reason"));
        entry.setTaskId(text(body, "taskId"));
        entry.setPerformedById(text(body, "performedById"));
        entry.setPerformedByName(text(body, "performedByName"));
        entry.setPerformedAt(LocalDateTime.now());
        operationRepository.save(entry);
    }

    private static int ageOf(Client client) {
        LocalDate birth = client.getBirthDate();
        if (birth == null) {
            return client.getAge() == null ? 0 : client.getAge();
        }
        return Period.between(birth, LocalDate.now()).getYears();
    }

    private static String text(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() || "null".equals(value) ? null : value;
    }

    private static String upper(Map<String, Object> body, String key) {
        String value = text(body, key);
        return value == null ? null : value.toUpperCase();
    }

    private static BigDecimal decimal(Map<String, Object> body, String key) {
        String value = text(body, key);
        try {
            return value == null ? null : new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(Map<String, Object> body, String key) {
        String value = text(body, key);
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
