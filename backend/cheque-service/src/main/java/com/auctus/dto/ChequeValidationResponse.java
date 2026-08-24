package com.auctus.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChequeValidationResponse {
    private String chequeId;
    private String chequeNumber;

    /** Authoritative amount shown to the agent: the plafond carried by the QR code. */
    private BigDecimal amount;
    /** Maximum amount the cheque may carry, as published in the QR code. */
    private BigDecimal plafond;
    /** Best-effort reading of the handwritten amount - advisory only, may be null. */
    private BigDecimal amountWritten;

    /** Account holder who issued and signed the cheque. */
    private String titulaire;
    private String beneficiary;

    private String status;
    private String rejectionReason;
    private Double confidenceScore;
    private Double signatureScore;
    private Double processingTime;
    private String validatedAt;

    private Map<String, Object> qrData;

    /** One entry per verification step, in the order they were run. */
    private List<Map<String, Object>> steps;
}
