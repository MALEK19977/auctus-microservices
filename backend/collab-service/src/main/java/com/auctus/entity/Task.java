package com.auctus.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A piece of work handed from one person to another.
 *
 * <p>This is the unit the branch actually coordinates on: "check this client's
 * cheque", "their account details are wrong", "they want a loan, read the file".
 * It carries who it concerns (the client), who must act (the assignee), by when,
 * and any paperwork needed to do it.
 */
@Entity
@Table(name = "tasks", indexes = {
        @Index(name = "idx_task_assignee", columnList = "assignedToId,status"),
        @Index(name = "idx_task_client", columnList = "clientRib")
})
@Data
public class Task {

    @Id
    private String id;

    private String title;

    @Column(length = 3000)
    private String description;

    /**
     * CHEQUE_REVIEW, ACCOUNT_CHANGE, CREDIT_REQUEST, DOCUMENT_CHECK or OTHER -
     * what kind of work this is, so a queue can be read at a glance.
     */
    private String category;

    /** The client account the work concerns. */
    private String clientRib;
    private String clientName;

    /** Set when the task is about a specific cheque awaiting a decision. */
    private String chequeId;
    private String chequeNumber;

    private String assignedToId;
    private String assignedToName;

    private String createdById;
    private String createdByName;
    private String createdByRole;

    private LocalDateTime startsAt;
    private LocalDateTime dueAt;

    /** PENDING, IN_PROGRESS, DONE or CANCELLED. */
    private String status;
    /** LOW, NORMAL, HIGH or URGENT. */
    private String priority;

    @Column(length = 2000)
    private String resolutionNote;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
