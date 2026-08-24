package com.auctus.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "conversation_members", indexes = {
        @Index(name = "idx_member_user", columnList = "userId"),
        @Index(name = "idx_member_conversation", columnList = "conversationId")
})
@Data
public class ConversationMember {

    @Id
    private String id;

    private String conversationId;
    private String userId;
    private String userName;
    private String userRole;

    /**
     * Everything sent after this instant is unread for this member. Storing a
     * timestamp rather than a per-message flag keeps group unread counts to one row.
     */
    private LocalDateTime lastReadAt;

    private LocalDateTime joinedAt;
}
