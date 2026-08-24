package com.auctus.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "messages", indexes = {
        @Index(name = "idx_msg_conversation", columnList = "conversationId,sentAt")
})
@Data
public class Message {

    @Id
    private String id;

    private String conversationId;

    private String senderId;
    private String senderName;

    @Column(length = 4000)
    private String body;

    private LocalDateTime sentAt;

    /** SYSTEM entries narrate membership changes inside the thread. */
    private String kind;

    /** Set when the message carries a photo, a voice note or a document. */
    private String attachmentId;
    private String attachmentKind;
    private String attachmentName;
}
