package com.auctus.repository;

import com.auctus.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, String> {

    List<Message> findByConversationIdOrderBySentAtAsc(String conversationId);

    /** Unread for a member: everything after their last read, excluding their own. */
    long countByConversationIdAndSentAtAfterAndSenderIdNot(
            String conversationId, LocalDateTime after, String senderId);

    long countByConversationIdAndSenderIdNot(String conversationId, String senderId);
}
