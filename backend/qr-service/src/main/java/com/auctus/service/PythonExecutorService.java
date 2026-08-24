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

    private static final int TIMEOUT_SECONDS = 60;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${python.script.reader}")
    private String readerScript;

    @Value("${python.script.verifier}")
    private String verifierScript;

    @Value("${python.command:}")
    private String configuredPythonCommand;

    private volatile String resolvedPythonCommand;

    private static final String[] PYTHON_CANDIDATES = {"python", "py", "python3"};

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

    /** Decode the QR code only. */
    public Map<String, Object> readQrCode(byte[] imageBytes, String fileName) {
        return runScript(readerScript, imageBytes, fileName);
    }

    /** Decode the QR code and cross-check it against the fields printed on the cheque. */
    public Map<String, Object> verifyCheque(byte[] imageBytes, String fileName) {
        return runScript(verifierScript, imageBytes, fileName);
    }

    private Map<String, Object> runScript(String script, byte[] imageBytes, String fileName) {
        Path tempFile = null;

        try {
            tempFile = Paths.get(System.getProperty("java.io.tmpdir"),
                    UUID.randomUUID() + "_" + sanitise(fileName));
            Files.write(tempFile, imageBytes);

            Path scriptPath = Paths.get(script).toAbsolutePath().normalize();
            if (!Files.exists(scriptPath)) {
                return failure("Python script not found: " + scriptPath);
            }

            ProcessBuilder builder = new ProcessBuilder(
                    pythonCommand(), scriptPath.toString(), tempFile.toString());
            log.info("Running: {}", String.join(" ", builder.command()));

            Process process = builder.start();

            // The scripts write JSON to stdout and diagnostics to stderr. Merging the
            // two streams would corrupt the JSON payload, so stderr is drained apart.
            Thread stderrPump = new Thread(() -> drain(process.getErrorStream()));
            stderrPump.setDaemon(true);
            stderrPump.start();

            String stdout = read(process.getInputStream());

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return failure("Python script timed out after " + TIMEOUT_SECONDS + "s");
            }
            stderrPump.join(TimeUnit.SECONDS.toMillis(2));

            String json = stdout.trim();
            log.info("Script exited with {} and returned {} chars", process.exitValue(), json.length());

            if (json.isEmpty()) {
                return failure("Python script produced no output (exit code " + process.exitValue() + ")");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(json, Map.class);
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("Processing was interrupted");
        } catch (Exception e) {
            log.error("Python execution failed", e);
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

    private void drain(InputStream stream) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[python] {}", line);
            }
        } catch (IOException e) {
            log.debug("Stopped reading stderr: {}", e.getMessage());
        }
    }

    private String sanitise(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "upload.png";
        }
        return Paths.get(fileName).getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Map<String, Object> failure(String message) {
        log.error("QR service error: {}", message);
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("error", message);
        return error;
    }
}
