package com.auctus.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "appointments", indexes = {
        @Index(name = "idx_appt_owner", columnList = "ownerId,startsAt")
})
@Data
public class Appointment {

    @Id
    private String id;

    /** Agent the appointment belongs to. */
    private String ownerId;
    private String ownerName;

    private String title;

    @Column(length = 2000)
    private String description;

    /** Optional link to a client file, so the agent can open it from the agenda. */
    private String clientRib;
    private String clientName;

    private String location;

    private LocalDateTime startsAt;
    private LocalDateTime endsAt;

    /** SCHEDULED, DONE or CANCELLED. */
    private String status;

    /** How long before the start a reminder is due, in minutes. */
    private Integer reminderMinutes;

    private String createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
