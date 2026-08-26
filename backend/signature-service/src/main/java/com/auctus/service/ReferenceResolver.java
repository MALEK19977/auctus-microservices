package com.auctus.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the signature specimen enrolled for an account.
 *
 * <p>The client register is the authority on who owns which specimen, so it is
 * asked rather than re-read from a CSV. The matcher used to resolve references
 * from the cheque generator's own export, which only ever knew the accounts that
 * export had created - every cheque drawn on a client added later came back
 * unverifiable, with no score at all.
 */
@Service
@Slf4j
public class ReferenceResolver {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${services.client.url:http://localhost:8086}")
    private String clientServiceUrl;

    @Value("${signature.reference-folder}")
    private String signaturesFolder;

    /**
     * Absolute path of the specimen registered for this account holder, if the
     * register knows them and the file is really there.
     *
     * @param identifier a RIB, an account number, a CIN or a full name - whatever
     *                   the cheque gave us.
     */
    public Optional<Path> resolve(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }

        Map<String, Object> client = lookup(identifier);
        if (client == null) {
            log.warn("Client register has no account matching '{}'", identifier);
            return Optional.empty();
        }

        String dossier = text(client.get("signatureDossier"));
        String image = text(client.get("signatureImage"));
        if (dossier == null || image == null) {
            log.warn("Account '{}' has no enrolled specimen", identifier);
            return Optional.empty();
        }

        Path path = Paths.get(signaturesFolder, dossier, image).normalize().toAbsolutePath();
        // The values come from the database, but a traversal here would read any
        // file on disk, so the result has to stay inside the specimen store.
        Path root = Paths.get(signaturesFolder).normalize().toAbsolutePath();
        if (!path.startsWith(root)) {
            log.warn("Refusing specimen path outside the store: {}", path);
            return Optional.empty();
        }
        if (!Files.isRegularFile(path)) {
            log.warn("Specimen registered for '{}' is missing on disk: {}", identifier, path);
            return Optional.empty();
        }
        return Optional.of(path);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lookup(String identifier) {
        String cleaned = identifier.replaceAll("\\s", "");

        // A RIB identifies exactly one account, so try that first and fall back to
        // the general search, which also accepts a name, a CIN or an account number.
        try {
            Map<String, Object> byRib = restTemplate.getForObject(
                    clientServiceUrl + "/api/client/rib/" + UriUtils.encodePathSegment(cleaned, StandardCharsets.UTF_8),
                    Map.class);
            if (byRib != null && byRib.get("signatureDossier") != null) {
                return byRib;
            }
        } catch (Exception e) {
            log.debug("No account for RIB '{}': {}", cleaned, e.getMessage());
        }

        try {
            Map<String, Object> response = restTemplate.getForObject(
                    clientServiceUrl + "/api/client/search?q="
                            + UriUtils.encodeQueryParam(identifier, StandardCharsets.UTF_8),
                    Map.class);
            if (response == null) {
                return null;
            }
            Object results = response.get("results");
            if (results instanceof java.util.List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> first) {
                return (Map<String, Object>) first;
            }
        } catch (Exception e) {
            log.warn("Client register unreachable while resolving '{}': {}", identifier, e.getMessage());
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
