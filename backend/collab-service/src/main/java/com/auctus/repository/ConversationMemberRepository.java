package com.auctus.repository;

import com.auctus.entity.ConversationMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationMemberRepository extends JpaRepository<ConversationMember, String> {

    List<ConversationMember> findByUserId(String userId);

    List<ConversationMember> findByConversationId(String conversationId);

    Optional<ConversationMember> findByConversationIdAndUserId(String conversationId, String userId);

    void deleteByConversationIdAndUserId(String conversationId, String userId);
}
