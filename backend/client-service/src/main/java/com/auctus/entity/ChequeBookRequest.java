package com.auctus.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A chequebook ordered for a client.
 *
 * <p>Under the 2025 reform every cheque carries a ceiling, so the ceiling is
 * chosen when the book is ordered and printed on each leaf.
 */
@Entity
@Table(name = "chequebook_requests", indexes = {
        @Index(name = "idx_book_client", columnList = "clientId,requestedAt")
})
@Data
public class ChequeBookRequest {

    @Id
    private String id;

    private String clientId;
    private String clientRib;
    private String clientName;

    private Integer leafCount;

    /** Per-cheque ceiling, capped at 30 000 DT by law. */
    @Column(precision = 15, scale = 3)
    private BigDecimal plafond;

    /** REQUESTED, APPROVED, PRINTED, DELIVERED or REFUSED. */
    private String status;

    @Column(length = 1000)
    private String note;

    private String taskId;

    private String requestedById;
    private String requestedByName;
    private LocalDateTime requestedAt;
    private LocalDateTime updatedAt;
}
