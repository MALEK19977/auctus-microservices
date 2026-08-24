package com.auctus.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A chat, whether between two people or a named group.
 *
 * <p>Direct chats and groups share one model so a message, an unread count and the
 * live push path work identically for both - only the member count differs.
 */
@Entity
@Table(name = "conversations")
@Data
public class Conversation {

    @Id
    private String id;

    /** DIRECT or GROUP. */
    private String type;

    /** Group title. Null for direct chats, which are titled by the other person. */
    private String name;

    private String createdBy;
    private String createdByName;

    private LocalDateTime createdAt;
    /** Drives the ordering of the conversation list. */
    private LocalDateTime lastMessageAt;

    @Column(length = 500)
    private String lastMessagePreview;
    private String lastMessageSender;
}
