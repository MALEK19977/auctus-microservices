package com.auctus.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A file attached to a task or a chat message - a scanned ID, a payslip, a photo
 * of a cheque, or a recorded voice note.
 */
@Entity
@Table(name = "attachments", indexes = {
        @Index(name = "idx_attach_owner", columnList = "ownerType,ownerId")
})
@Data
public class Attachment {

    @Id
    private String id;

    /** TASK or MESSAGE. */
    private String ownerType;
    private String ownerId;

    private String fileName;
    private String contentType;
    private Long sizeBytes;

    /** IMAGE, AUDIO or DOCUMENT - decides how the client renders it. */
    private String kind;

    /** Name on disk; the original file name is kept separately for display. */
    private String storedName;

    private String uploadedById;
    private String uploadedByName;
    private LocalDateTime uploadedAt;
}
