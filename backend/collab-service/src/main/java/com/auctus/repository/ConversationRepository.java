package com.auctus.repository;

import com.auctus.entity.Conversation;
import com.auctus.entity.ConversationMember;
import com.auctus.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, String> {
}
