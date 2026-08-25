package com.auctus.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "cheques")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Cheque {
    @Id
    private String id;
    private String chequeNumber;
    /** Authoritative amount for display: the plafond carried by the QR code. */
    private BigDecimal amount;
    /** Ceiling published in the QR code (plafond), introduced by the 2025 reform. */
    private BigDecimal plafond;
    /** Best-effort reading of the handwritten amount - advisory only, may be null. */
    private BigDecimal amountWritten;
    /** Account holder who issued and signed the cheque. */
    private String issuerName;
    private String beneficiaryName;
    private String issuerRib;
    private String beneficiaryRib;
    private String expiryDate;
    
    @Enumerated(EnumType.STRING)
    private ChequeStatus status;
    
    private String rejectionReason;
    private Double signatureScore;
    private Double processingTime;
    private String validatedBy;
    /** Readable agent name - an id like "4" tells an admin nothing. */
    private String validatedByName;
    
    // Plain TEXT, deliberately not @Lob: on PostgreSQL that maps a String to a
    // large object (OID), which cannot be read back in auto-commit mode and made
    // every read of these rows fail with "Large Objects may not be used in
    // auto-commit mode".
    @Column(columnDefinition = "TEXT")
    private String ocrResult;

    @Column(columnDefinition = "TEXT")
    private String qrData;
    
    private LocalDateTime createdAt;
    private LocalDateTime validatedAt;

    /**
     * File name of the cheque image as stored on disk. An administrator reviewing
     * a rejection has to see the document the agent saw, so the upload is kept
     * rather than discarded once the services have read it.
     */
    private String imageName;

    /**
     * Set when an administrator has looked at this cheque. A rejection stays
     * rejected; this only records that somebody has seen it, which is what the
     * oversight badge counts down.
     */
    private LocalDateTime reviewedAt;
    private String reviewedBy;
}
