package com.auctus.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A credit file opened for a client.
 *
 * <p>Covers the three products the branch offers: consumer credit, housing credit
 * and the comfort savings loan. The agent assembles the file; an administrator
 * approves or refuses it.
 */
@Entity
@Table(name = "credit_applications", indexes = {
        @Index(name = "idx_credit_client", columnList = "clientId,createdAt")
})
@Data
public class CreditApplication {

    @Id
    private String id;

    private String clientId;
    private String clientRib;
    private String clientName;

    /** CONSUMER, HOUSING or COMFORT_SAVINGS. */
    private String type;

    @Column(precision = 15, scale = 3)
    private BigDecimal amount;
    private Integer durationMonths;

    @Column(precision = 15, scale = 3)
    private BigDecimal monthlyIncome;

    /** Computed at submission: monthly instalment against declared income. */
    @Column(precision = 6, scale = 2)
    private BigDecimal debtRatio;

    @Column(precision = 15, scale = 3)
    private BigDecimal monthlyInstalment;

    /** DRAFT, SUBMITTED, APPROVED or REFUSED. */
    private String status;

    @Column(length = 1000)
    private String purpose;

    @Column(length = 1000)
    private String decisionNote;

    /** Comma-separated list of documents the agent ticked off. */
    @Column(length = 1000)
    private String documentsProvided;

    private String taskId;

    private String createdById;
    private String createdByName;
    private String decidedById;
    private String decidedByName;

    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;
}
