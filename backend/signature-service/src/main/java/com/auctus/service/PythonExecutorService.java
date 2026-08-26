package com.auctus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class PythonExecutorService {

    private static final int TIMEOUT_SECONDS = 30;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ReferenceResolver referenceResolver;

    public PythonExecutorService(ReferenceResolver referenceResolver) {
        this.referenceResolver = referenceResolver;
    }

    @Value("${signature.reference-folder}")
    private String signaturesFolder;

    @Value("${signature.csv.path}")
    private String chequesCsvPath;

    @Value("${python.script.matcher}")
    private String matcherScript;

    @Value("${python.command:}")
    private String configuredPythonCommand;

    private volatile String resolvedPythonCommand;

    private static final String[] PYTHON_CANDIDATES = {
        "python", "py", "python3", "C:\\Python311\\python.exe", "C:\\Python39\\python.exe"
    };

    private String pythonCommand() {
        if (configuredPythonCommand != null && !configuredPythonCommand.isBlank()) {
            return configuredPythonCommand;
        }
        if (resolvedPythonCommand != null) {
            return resolvedPythonCommand;
        }
        for (String candidate : PYTHON_CANDIDATES) {
            try {
                Process process = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true)
                        .start();
                if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    log.info("Using Python command: {}", candidate);
                    resolvedPythonCommand = candidate;
                    return candidate;
                }
                process.destroyForcibly();
            } catch (IOException e) {
                // Candidate is not installed - try the next one.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("No Python interpreter responded to --version, falling back to 'python'");
        resolvedPythonCommand = "python";
        return resolvedPythonCommand;
    }

    public Map<String, Object> verifySignatureByTitulaire(byte[] imageBytes, String fileName, String titulaire) {
        Path tempFile = null;

        try {
            tempFile = Paths.get(System.getProperty("java.io.tmpdir"),
                    UUID.randomUUID() + "_" + sanitise(fileName));
            Files.write(tempFile, imageBytes);

            Path scriptPath = Paths.get(matcherScript).toAbsolutePath().normalize();
            if (!Files.exists(scriptPath)) {
                return failure("Signature matcher script not found: " + scriptPath);
            }
            if (!Files.isDirectory(Paths.get(signaturesFolder))) {
                return failure("Signatures folder not found: " + signaturesFolder);
            }

            // Ask the client register which specimen belongs to this account. When
            // it answers, the matcher is handed the file directly and does no
            // lookup of its own; otherwise it falls back to its CSV.
            String referencePath = referenceResolver.resolve(titulaire)
                    .map(Path::toString)
                    .orElse("");

            ProcessBuilder builder = new ProcessBuilder(
                    pythonCommand(),
                    scriptPath.toString(),
                    tempFile.toString(),
                    signaturesFolder,
                    titulaire,
                    chequesCsvPath,
                    referencePath
            );
            log.info("Running: {}", String.join(" ", builder.command()));

            Process process = builder.start();

            // The script writes its JSON answer to stdout and diagnostics to stderr.
            // They must stay separate - merging them corrupts the JSON payload.
            Thread stderrPump = new Thread(() -> drain(process.getErrorStream(), "python"));
            stderrPump.setDaemon(true);
            stderrPump.start();

            String stdout = read(process.getInputStream());

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return failure("Signature matcher timed out after " + TIMEOUT_SECONDS + "s");
            }
            stderrPump.join(TimeUnit.SECONDS.toMillis(2));

            int exitCode = process.exitValue();
            String json = stdout.trim();
            log.info("Matcher exited with {} and returned: {}", exitCode, json);

            if (json.isEmpty()) {
                return failure("Signature matcher produced no output (exit code " + exitCode + ")");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(json, Map.class);
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("Signature verification was interrupted");
        } catch (Exception e) {
            log.error("Signature verification failed", e);
            return failure(e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("Could not delete temp file {}: {}", tempFile, e.getMessage());
                }
            }
        }
    }

    private String read(InputStream stream) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        return output.toString();
    }

    private void drain(InputStream stream, String prefix) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[{}] {}", prefix, line);
            }
        } catch (IOException e) {
            log.debug("Stopped reading {} stream: {}", prefix, e.getMessage());
        }
    }

    private String sanitise(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "upload.png";
        }
        return Paths.get(fileName).getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Map<String, Object> failure(String message) {
        log.error("Signature verification error: {}", message);
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("error", message);
        return error;
    }
}
