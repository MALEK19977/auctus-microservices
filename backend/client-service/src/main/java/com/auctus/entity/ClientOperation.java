package com.auctus.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * An audit line for every change made to a client file.
 *
 * <p>In a bank nothing may change without a trace of who changed it, what it was
 * before, what it became and why. Every operation endpoint writes one of these
 * before returning.
 */
@Entity
@Table(name = "client_operations", indexes = {
        @Index(name = "idx_op_client", columnList = "clientId,performedAt")
})
@Data
public class ClientOperation {

    @Id
    private String id;

    private String clientId;
    private String clientRib;

    /**
     * ACCOUNT_TYPE_CHANGE, ACCOUNT_STATUS_CHANGE, CONTACT_UPDATE,
     * CREDIT_APPLICATION, CHEQUEBOOK_REQUEST or MINOR_TO_ADULT.
     */
    private String operation;

    @Column(length = 500)
    private String previousValue;

    @Column(length = 500)
    private String newValue;

    @Column(length = 1000)
    private String reason;

    /** The task this was carried out for, when it came from the work queue. */
    private String taskId;

    private String performedById;
    private String performedByName;
    private LocalDateTime performedAt;
}
