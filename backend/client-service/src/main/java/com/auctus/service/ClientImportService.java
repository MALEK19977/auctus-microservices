package com.auctus.service;

import com.auctus.entity.Client;
import com.auctus.repository.ClientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the master client file into PostgreSQL on startup.
 *
 * <p>The CSV is the source of truth for who exists; the database is where the
 * platform reads from. Re-importing refreshes balances and contact details but
 * never reassigns a client's RIB, account number or signature - those are the
 * client's identity, and changing them would invalidate every cheque and credit
 * file that points at them.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ClientImportService implements ApplicationRunner {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ClientRepository clientRepository;

    @Value("${clients.master-file}")
    private String masterFile;

    @Value("${clients.import-on-startup:true}")
    private boolean importOnStartup;

    @Override
    public void run(ApplicationArguments args) {
        if (!importOnStartup) {
            log.info("Client import disabled; {} clients already in database", clientRepository.count());
            return;
        }
        try {
            int imported = importFromMasterFile();
            log.info("Client referential ready: {} clients ({} rows imported)",
                    clientRepository.count(), imported);
        } catch (IOException e) {
            log.error("Could not import the master client file: {}", e.getMessage());
        }
    }

    @Transactional
    public int importFromMasterFile() throws IOException {
        Path path = Paths.get(masterFile);
        if (!Files.exists(path)) {
            log.warn("Master client file not found: {}", path.toAbsolutePath());
            return 0;
        }

        List<Client> batch = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return 0;
            }
            List<String> headers = splitCsv(stripBom(headerLine));

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, String> row = toRow(headers, splitCsv(line));
                Client client = toClient(row);
                if (client != null) {
                    batch.add(client);
                }
            }
        }

        for (Client incoming : batch) {
            clientRepository.findById(incoming.getClientId()).ifPresentOrElse(existing -> {
                // Identity fields are deliberately left untouched on re-import.
                existing.setBalance(incoming.getBalance());
                existing.setPhone(incoming.getPhone());
                existing.setEmail(incoming.getEmail());
                existing.setAddress(incoming.getAddress());
                existing.setCity(incoming.getCity());
                existing.setStatus(incoming.getStatus());
                existing.setUpdatedAt(LocalDateTime.now());
                clientRepository.save(existing);
            }, () -> clientRepository.save(incoming));
        }
        return batch.size();
    }

    private Client toClient(Map<String, String> row) {
        String rib = row.get("rib");
        if (rib == null || rib.isBlank()) {
            return null;
        }

        Client client = new Client();
        client.setClientId(row.get("client_id"));
        client.setClientType(row.getOrDefault("client_type", "INDIVIDUAL"));
        client.setFirstName(row.get("first_name"));
        client.setLastName(row.get("last_name"));
        client.setFullName(row.get("full_name"));
        client.setCin(row.get("cin"));
        client.setBirthDate(parseDate(row.get("birth_date")));
        client.setAge(parseInt(row.get("age")));
        client.setMinor("true".equalsIgnoreCase(row.get("is_minor")));
        client.setGuardianName(blankToNull(row.get("guardian_name")));
        client.setGuardianCin(blankToNull(row.get("guardian_cin")));
        client.setCity(row.get("city"));
        client.setAddress(row.get("address"));
        client.setPhone(row.get("phone"));
        client.setEmail(row.get("email"));
        client.setRib(rib);
        client.setAccountNumber(row.get("account_number"));
        client.setAccountType(row.get("account_type"));
        client.setAgencyCode(row.get("agency_code"));
        client.setAgencyName(row.get("agency_name"));
        client.setAgencyAddress(row.get("agency_address"));
        client.setBalance(parseDecimal(row.get("balance")));
        client.setSignatureDossier(row.get("signature_dossier"));
        client.setSignatureImage(row.get("signature_image"));
        client.setStatus(row.getOrDefault("status", "ACTIVE"));
        client.setCreatedAt(LocalDateTime.now());
        client.setUpdatedAt(LocalDateTime.now());
        return client;
    }

    // --- tiny CSV reader: the master file has no embedded newlines ------------

    private static List<String> splitCsv(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString().trim());
        return values;
    }

    private static Map<String, String> toRow(List<String> headers, List<String> values) {
        Map<String, String> row = new HashMap<>();
        for (int i = 0; i < headers.size() && i < values.size(); i++) {
            row.put(headers.get(i), values.get(i));
        }
        return row;
    }

    private static String stripBom(String value) {
        return value.startsWith("﻿") ? value.substring(1) : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static LocalDate parseDate(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value, DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer parseInt(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(String value) {
        try {
            return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** Exposed for the admin endpoint that re-syncs the referential on demand. */
    public List<String> expectedHeaders() {
        return Arrays.asList("client_id", "full_name", "rib", "account_number", "balance");
    }
}
