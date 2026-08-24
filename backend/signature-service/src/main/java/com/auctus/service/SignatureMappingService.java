package com.auctus.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class SignatureMappingService {

    @Value("${signature.reference-folder}")
    private String referenceFolder;
    
    @Value("${signature.csv.path}")
    private String csvPath;

    private final Map<String, String> titulaireToSignaturePath = new HashMap<>();

    @PostConstruct
    public void loadMappings() {
        log.info("Loading signature mappings from: {}", referenceFolder);
        loadFromChequesCsv();
        log.info("Loaded {} signature mappings", titulaireToSignaturePath.size());
    }

    private void loadFromChequesCsv() {
        Path csvFile = Paths.get(csvPath);
        if (!Files.exists(csvFile)) {
            log.warn("CSV file not found: {}", csvPath);
            return;
        }
        
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String line;
            boolean firstLine = true;
            
            while ((line = br.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    continue;
                }
                
                String[] columns = line.split(",");
                if (columns.length >= 6) {
                    String titulaire = cleanValue(columns[2]);
                    String signatureDossier = cleanValue(columns[4]);
                    String signatureImage = cleanValue(columns[5]);
                    
                    String signaturePath = Paths.get(referenceFolder, signatureDossier, signatureImage).toString();
                    titulaireToSignaturePath.put(titulaire.toUpperCase(), signaturePath);
                    log.info("Loaded: {} -> {}", titulaire, signaturePath);
                }
            }
        } catch (IOException e) {
            log.error("Error loading cheques.csv: {}", e.getMessage());
        }
    }
    
    private String cleanValue(String value) {
        return value.replace("\"", "").trim();
    }

    public String getSignaturePath(String titulaire) {
        if (titulaire == null) return null;
        String key = titulaire.toUpperCase().trim();
        String path = titulaireToSignaturePath.get(key);
        if (path == null) {
            log.warn("No signature found for titulaire: {}", titulaire);
        }
        return path;
    }
    
    public Map<String, String> getAllMappings() {
        return new HashMap<>(titulaireToSignaturePath);
    }
}