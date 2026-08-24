package com.auctus.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "qr_scan_history")
@Data
public class QrScanHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String chequeNumber;
    private String ribTitulaire;
    private String titulaire;
    private BigDecimal maxAmount;
    private LocalDate expiryDate;
    private String receiverRib;
    private LocalDateTime scanDate;
    private String scannedBy;
    private String imagePath;
    private String status;
    
    @PrePersist
    protected void onCreate() {
        scanDate = LocalDateTime.now();
        if (status == null) status = "VALIDATED";
    }
}