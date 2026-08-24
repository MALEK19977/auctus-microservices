package com.auctus.service;

import com.auctus.entity.Client;
import com.auctus.repository.ClientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class ClientService {

    private final ClientRepository clientRepository;

    @Value("${clients.signatures-folder}")
    private String signaturesFolder;

    public List<Client> search(String term) {
        String cleaned = normalise(term);
        return cleaned.isEmpty() ? List.of() : clientRepository.search(cleaned);
    }

    public Optional<Client> findByRib(String rib) {
        return clientRepository.findByRib(normalise(rib));
    }

    public Optional<Client> findByAccountNumber(String accountNumber) {
        return clientRepository.findByAccountNumber(normalise(accountNumber));
    }

    public Optional<Client> findById(String clientId) {
        return clientRepository.findById(clientId);
    }

    /**
     * Checks that the account behind {@code rib} exists, is active and holds enough
     * money to honour {@code amount}.
     *
     * <p>When the cheque amount could not be read, {@code plafond} still allows a
     * useful answer: an account holding more than the ceiling can honour the cheque
     * whatever it turns out to be.
     */
    public Map<String, Object> checkFunds(String rib, BigDecimal amount) {
        return checkFunds(rib, amount, null);
    }

    public Map<String, Object> checkFunds(String rib, BigDecimal amount, BigDecimal plafond) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rib", rib);
        result.put("amount", amount);
        result.put("plafond", plafond);

        Optional<Client> found = findByRib(rib);
        if (found.isEmpty()) {
            result.put("status", "ACCOUNT_NOT_FOUND");
            result.put("sufficient", false);
            result.put("message", "Aucun compte ne correspond à ce RIB");
            return result;
        }

        Client client = found.get();
        result.put("clientId", client.getClientId());
        result.put("clientName", client.getFullName());
        result.put("accountType", client.getAccountType());
        result.put("balance", client.getBalance());

        if (!"ACTIVE".equalsIgnoreCase(client.getStatus())) {
            result.put("status", "ACCOUNT_INACTIVE");
            result.put("sufficient", false);
            result.put("message", "Le compte n'est pas actif (" + client.getStatus() + ")");
            return result;
        }

        if (amount == null) {
            result.put("status", "AMOUNT_UNKNOWN");
            result.put("sufficient", null);
            result.put("message", "Montant du chèque non déterminé : provision à vérifier manuellement");
            return result;
        }

        BigDecimal balance = client.getBalance() == null ? BigDecimal.ZERO : client.getBalance();
        boolean sufficient = balance.compareTo(amount) >= 0;
        result.put("sufficient", sufficient);
        result.put("status", sufficient ? "SUFFICIENT" : "INSUFFICIENT_FUNDS");
        result.put("shortfall", sufficient ? BigDecimal.ZERO : amount.subtract(balance));
        result.put("message", sufficient
                ? "Provision suffisante"
                : "Provision insuffisante : solde " + balance + " DT pour un chèque de " + amount + " DT");
        return result;
    }

    /** Reads the client's enrolled signature image from the signature store. */
    public Optional<byte[]> readSignatureSpecimen(String clientId) {
        Optional<Client> found = clientRepository.findById(clientId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Client client = found.get();
        if (client.getSignatureDossier() == null || client.getSignatureImage() == null) {
            return Optional.empty();
        }

        Path path = Paths.get(signaturesFolder, client.getSignatureDossier(), client.getSignatureImage())
                .normalize();
        // Values come from the database, but a path traversal here would expose
        // arbitrary files, so the result must stay inside the signature store.
        if (!path.startsWith(Paths.get(signaturesFolder).normalize())) {
            log.warn("Rejected signature path outside the store: {}", path);
            return Optional.empty();
        }
        if (!Files.isRegularFile(path)) {
            log.warn("Signature specimen missing on disk: {}", path);
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(path));
        } catch (IOException e) {
            log.error("Could not read signature specimen {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    private static String normalise(String value) {
        return value == null ? "" : value.replaceAll("\\s", "").trim();
    }
}
