package com.auctus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class PythonExecutorService {

    @Value("${python.script.path}")
    private String pythonScriptPath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> detectCheque(byte[] imageBytes, String fileName) {
        Path tempFile = null;
        
        try {
            String tempDir = System.getProperty("java.io.tmpdir");
            String tempFileName = UUID.randomUUID().toString() + "_" + fileName;
            tempFile = Paths.get(tempDir, tempFileName);
            Files.write(tempFile, imageBytes);
            
            // CHANGER "python" en "py" pour Windows
            ProcessBuilder pb = new ProcessBuilder("py", pythonScriptPath, tempFile.toString());
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
            
            process.waitFor();
            String jsonOutput = output.toString().trim();
            log.info("Python output: {}", jsonOutput);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(jsonOutput, Map.class);
            return result;
            
        } catch (Exception e) {
            log.error("Error executing Python script: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("is_cheque", false);
            error.put("error", e.getMessage());
            return error;
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException e) {}
            }
        }
    }
}