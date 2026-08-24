package com.auctus.service;

import com.auctus.dto.ChequeValidationResponse;
import com.auctus.entity.Cheque;
import com.auctus.entity.ChequeStatus;
import com.auctus.repository.ChequeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs the three verification steps of the Auctus pipeline and turns their
 * results into a single verdict:
 * <ol>
 *   <li>the uploaded image really is a cheque (OCR service);</li>
 *   <li>the QR code agrees with what is printed on the cheque, and the cheque
 *       respects the plafond / expiry rules of the 2025 Tunisian reform
 *       (QR service);</li>
 *   <li>the signature matches the one registered for the account holder
 *       (signature service).</li>
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChequeValidationService {

    private final ChequeRepository chequeRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${services.ocr.url:http://localhost:8083}")
    private String ocrServiceUrl;

    @Value("${services.qr.url:http://localhost:8084}")
    private String qrServiceUrl;

    @Value("${services.signature.url:http://localhost:8085}")
    private String signatureServiceUrl;

    @Value("${services.client.url:http://localhost:8086}")
    private String clientServiceUrl;

    @Transactional
    public ChequeValidationResponse validateCheque(MultipartFile image, String agentId) {
        return validateCheque(image, agentId, agentId);
    }

    @Transactional
    public ChequeValidationResponse validateCheque(MultipartFile image, String agentId, String agentName) {
        long startTime = System.currentTimeMillis();
        List<Map<String, Object>> steps = new ArrayList<>();

        try {
            byte[] bytes = image.getBytes();
            String fileName = image.getOriginalFilename();

            // --- Step 1: is this really a cheque? ------------------------------
            Map<String, Object> ocr = post(ocrServiceUrl + "/api/ocr/detect", bytes, fileName);
            boolean isCheque = asBoolean(ocr.get("is_cheque"));
            double confidence = asDouble(ocr.get("confidence"), 0.0);

            steps.add(step(1, "Authenticité du document", isCheque ? "PASS" : "FAIL",
                    isCheque ? "Document reconnu comme un chèque"
                             : "L'image ne correspond pas à un chèque",
                    Map.of("confidence", confidence)));

            if (!isCheque) {
                return reject("L'image téléchargée n'est pas un chèque", startTime, steps, null);
            }

            // --- Step 2: QR code versus the printed cheque ---------------------
            Map<String, Object> qr = post(qrServiceUrl + "/api/qr/verify", bytes, fileName);
            String qrVerdict = asString(qr.get("verdict"));
            Map<String, Object> qrFields = asMap(qr.get("qr"));
            List<Map<String, Object>> qrChecks = asList(qr.get("checks"));

            if (!asBoolean(qr.get("success"))) {
                String reason = asString(qr.get("error"));
                steps.add(step(2, "Cohérence QR / chèque", "FAIL",
                        reason != null ? reason : "Vérification du QR code impossible", null));
                return reject("Vérification du QR code impossible", startTime, steps, null);
            }

            steps.add(step(2, "Cohérence QR / chèque",
                    "REJECTED".equals(qrVerdict) ? "FAIL" : "ACCEPTED".equals(qrVerdict) ? "PASS" : "REVIEW",
                    describe(qrChecks),
                    Map.of("checks", qrChecks == null ? Collections.emptyList() : qrChecks,
                           "handwritten", asMap(qr.get("handwritten")) == null
                                   ? Collections.emptyMap() : asMap(qr.get("handwritten")))));

            String titulaire = asString(qrFields == null ? null : qrFields.get("titulaire"));
            String holderRib = text(qrFields, "rib_titulaire");

            // --- Step 3: signature of the account holder -----------------------
            Double signatureScore = null;
            String signatureStatus;
            String signatureDetail;

            if (titulaire == null || titulaire.isBlank()) {
                signatureStatus = "REVIEW";
                signatureDetail = "Titulaire inconnu : signature non vérifiable";
            } else {
                Map<String, Object> signature = postSignature(bytes, fileName, titulaire, holderRib);
                if (!asBoolean(signature.get("success"))) {
                    signatureStatus = "REVIEW";
                    signatureDetail = asString(signature.get("error"));
                    if (signatureDetail == null) {
                        signatureDetail = "Vérification de la signature impossible";
                    }
                } else {
                    signatureScore = asDouble(signature.get("score"), 0.0);
                    boolean valid = asBoolean(signature.get("is_valid"));
                    signatureStatus = valid ? "PASS" : "FAIL";
                    signatureDetail = valid
                            ? String.format("Signature conforme à celle de %s", titulaire)
                            : String.format("La signature ne correspond pas à celle de %s", titulaire);
                }
            }

            Map<String, Object> signatureData = new LinkedHashMap<>();
            signatureData.put("titulaire", titulaire);
            signatureData.put("score", signatureScore);
            steps.add(step(3, "Signature du titulaire", signatureStatus, signatureDetail, signatureData));

            // --- Step 4: is there money behind the cheque? ----------------------
            Map<String, Object> handwrittenAmounts = asMap(qr.get("handwritten"));
            BigDecimal plafondValue = toDecimal(qrFields == null ? null : qrFields.get("plafond"));
            BigDecimal confirmedAmount = confidentAmount(handwrittenAmounts);
            String issuerRib = holderRib;

            // Only the amount actually written can be checked against the balance.
            // The plafond is a ceiling, not a debit, so an account below it proves
            // nothing about a cheque drawn for far less.
            Map<String, Object> funds = checkFunds(issuerRib, confirmedAmount);
            String fundsStatus = fundsStatus(funds, confirmedAmount);
            steps.add(step(4, "Provision du compte", fundsStatus,
                    asString(funds.get("message")), funds));

            // --- Verdict --------------------------------------------------------
            ChequeStatus status;
            String rejectionReason = null;
            // Forgery outranks everything: a tampered cheque or a bad signature is a
            // document problem, an unfunded one is only a payment problem.
            if ("REJECTED".equals(qrVerdict)) {
                status = ChequeStatus.REJECTED;
                rejectionReason = "Le QR code ne correspond pas au chèque : " + describe(qrChecks);
            } else if ("FAIL".equals(signatureStatus)) {
                status = ChequeStatus.REJECTED;
                rejectionReason = signatureDetail;
            } else if ("FAIL".equals(fundsStatus)) {
                status = ChequeStatus.REJECTED;
                rejectionReason = asString(funds.get("message"));
            } else if ("REVIEW".equals(qrVerdict) || "REVIEW".equals(signatureStatus)
                    || "REVIEW".equals(fundsStatus)) {
                status = ChequeStatus.REVIEW;
            } else {
                status = ChequeStatus.ACCEPTED;
            }

            // --- Persist ---------------------------------------------------------
            // The handwritten amount is only advisory - reading cursive script is not
            // reliable enough to display as "the" amount. Report the plafond, which is
            // authoritative because it is signed into the QR code, and expose the
            // handwritten reading separately for the agent to confirm.
            BigDecimal amountWritten = confirmedAmount;
            if (amountWritten == null && handwrittenAmounts != null) {
                amountWritten = toDecimal(handwrittenAmounts.get("amount_words"));
                if (amountWritten == null) {
                    amountWritten = toDecimal(handwrittenAmounts.get("amount_digits"));
                }
            }
            BigDecimal plafond = plafondValue;
            BigDecimal amount = plafond;

            // The QR strips spaces out of the holder name, so "SALMA BOUAZIZI" arrives
            // as "SALMABOUAZIZI". The client record holds the properly spelled name.
            String displayName = asString(funds.get("clientName"));
            if (displayName == null || displayName.isBlank()) {
                displayName = titulaire;
            }

            Cheque cheque = new Cheque();
            cheque.setId(UUID.randomUUID().toString());
            cheque.setChequeNumber(text(qrFields, "cheque_number"));
            cheque.setAmount(amount);
            cheque.setPlafond(plafond);
            cheque.setAmountWritten(amountWritten);
            cheque.setIssuerName(displayName);
            cheque.setIssuerRib(text(qrFields, "rib_titulaire"));
            cheque.setBeneficiaryRib(text(qrFields, "rib_beneficiaire"));
            cheque.setExpiryDate(text(qrFields, "expiry_date"));
            cheque.setStatus(status);
            cheque.setRejectionReason(rejectionReason);
            cheque.setSignatureScore(signatureScore);
            cheque.setValidatedBy(agentId);
            cheque.setValidatedByName(agentName);
            cheque.setValidatedAt(LocalDateTime.now());
            cheque.setCreatedAt(LocalDateTime.now());
            cheque.setProcessingTime((System.currentTimeMillis() - startTime) / 1000.0);
            cheque.setQrData(toJson(qr));
            cheque.setOcrResult(toJson(ocr));
            chequeRepository.save(cheque);

            return ChequeValidationResponse.builder()
                    .chequeId(cheque.getId())
                    .chequeNumber(cheque.getChequeNumber())
                    .amount(amount)
                    .plafond(plafond)
                    .amountWritten(amountWritten)
                    .titulaire(displayName)
                    .status(status.name())
                    .rejectionReason(rejectionReason)
                    .confidenceScore(confidence)
                    .signatureScore(signatureScore)
                    .processingTime(cheque.getProcessingTime())
                    .validatedAt(cheque.getValidatedAt().toString())
                    .qrData(qrFields)
                    .steps(steps)
                    .build();

        } catch (Exception e) {
            log.error("Cheque validation failed", e);
            return reject("Erreur de traitement : " + e.getMessage(), startTime, steps, null);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> step(int index, String name, String status,
                                     String detail, Map<String, Object> data) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", index);
        entry.put("name", name);
        entry.put("status", status);
        entry.put("detail", detail);
        if (data != null) {
            entry.put("data", data);
        }
        return entry;
    }

    /** Short human summary of the failing/uncertain QR checks. */
    private String describe(List<Map<String, Object>> checks) {
        if (checks == null || checks.isEmpty()) {
            return "Aucun contrôle effectué";
        }
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> check : checks) {
            String status = asString(check.get("status"));
            if ("FAIL".equals(status) || "WARN".equals(status)) {
                problems.add(asString(check.get("label")));
            }
        }
        if (problems.isEmpty()) {
            return "Tous les champs du QR code correspondent au chèque";
        }
        return String.join(", ", problems);
    }

    private ChequeValidationResponse reject(String reason, long startTime,
                                            List<Map<String, Object>> steps,
                                            Map<String, Object> qrData) {
        return ChequeValidationResponse.builder()
                .chequeId(UUID.randomUUID().toString())
                .status(ChequeStatus.REJECTED.name())
                .rejectionReason(reason)
                .processingTime((System.currentTimeMillis() - startTime) / 1000.0)
                .steps(steps)
                .qrData(qrData)
                .build();
    }

    private Map<String, Object> post(String url, byte[] bytes, String fileName) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        log.info("Calling {}", url);
        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = response.getBody();
        return result == null ? Collections.emptyMap() : result;
    }

    private Map<String, Object> postSignature(byte[] bytes, String fileName,
                                              String titulaire, String rib) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        body.add("titulaire", titulaire);
        // The RIB identifies the holder unambiguously; names are not unique.
        if (rib != null && !rib.isBlank()) {
            body.add("rib", rib);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        String url = signatureServiceUrl + "/api/signature/verify-by-titulaire";
        log.info("Calling {} for titulaire {}", url, titulaire);
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = response.getBody();
            return result == null ? Collections.emptyMap() : result;
        } catch (Exception e) {
            log.error("Signature service call failed: {}", e.getMessage());
            return Map.of("success", false, "error", "Service de signature indisponible");
        }
    }

    /**
     * The handwritten amount, but only when the words and the figures agree - two
     * independent readings landing on the same number is the only evidence strong
     * enough to act on.
     */
    private static BigDecimal confidentAmount(Map<String, Object> handwritten) {
        if (handwritten == null) {
            return null;
        }
        BigDecimal words = toDecimal(handwritten.get("amount_words"));
        BigDecimal digits = toDecimal(handwritten.get("amount_digits"));
        return words != null && words.equals(digits) ? words : null;
    }

    private Map<String, Object> checkFunds(String rib, BigDecimal amount) {
        if (rib == null || rib.isBlank()) {
            return Map.of("status", "UNKNOWN_RIB", "sufficient", false,
                    "message", "RIB du titulaire absent du QR code");
        }
        try {
            StringBuilder url = new StringBuilder(clientServiceUrl)
                    .append("/api/client/funds?rib=").append(rib);
            if (amount != null) {
                url.append("&amount=").append(amount.toPlainString());
            }
            log.info("Calling {}", url);
            ResponseEntity<Map> response = restTemplate.getForEntity(url.toString(), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = response.getBody();
            return result == null ? Map.of("status", "UNAVAILABLE", "message",
                    "Service client indisponible") : result;
        } catch (Exception e) {
            log.error("Client service call failed: {}", e.getMessage());
            return Map.of("status", "UNAVAILABLE", "message",
                    "Service client indisponible : provision non vérifiée");
        }
    }

    /**
     * Turns the client service answer into a step status. An account that cannot
     * cover the cheque is a rejection; an account that merely cannot cover the
     * whole plafond proves nothing, because the cheque is probably for less.
     */
    private static String fundsStatus(Map<String, Object> funds, BigDecimal confirmedAmount) {
        String status = asString(funds.get("status"));
        if ("SUFFICIENT".equals(status)) {
            return "PASS";
        }
        if ("ACCOUNT_NOT_FOUND".equals(status) || "ACCOUNT_INACTIVE".equals(status)) {
            return "FAIL";
        }
        if ("INSUFFICIENT_FUNDS".equals(status)) {
            // Only conclusive when we know what the cheque is actually for.
            return confirmedAmount != null ? "FAIL" : "INFO";
        }
        // AMOUNT_UNKNOWN: the account is fine, we just cannot read the amount. The
        // balance is reported so the agent can compare it against the cheque.
        return "AMOUNT_UNKNOWN".equals(status) ? "INFO" : "REVIEW";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean b && b;
    }

    private static double asDouble(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : null;
    }

    private static String text(Map<String, Object> map, String key) {
        return map == null ? null : asString(map.get(key));
    }

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.longValue());
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
