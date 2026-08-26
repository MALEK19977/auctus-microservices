package com.auctus.controller;

import com.auctus.entity.Attachment;
import com.auctus.entity.Conversation;
import com.auctus.entity.ConversationMember;
import com.auctus.entity.Message;
import com.auctus.repository.ConversationMemberRepository;
import com.auctus.repository.ConversationRepository;
import com.auctus.repository.MessageRepository;
import com.auctus.service.FileStore;
import com.auctus.service.LiveHub;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202"})
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final LiveHub liveHub;
    private final FileStore fileStore;

    /** The live stream a signed-in user listens on. */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("userId") String userId) {
        return liveHub.subscribe(userId);
    }

    // ------------------------------------------------------------ conversations

    @GetMapping("/conversations")
    public ResponseEntity<Map<String, Object>> conversations(@RequestParam("userId") String userId) {
        List<ConversationMember> mine = memberRepository.findByUserId(userId);

        List<Map<String, Object>> rows = mine.stream().map(membership -> {
            Conversation conversation = conversationRepository.findById(membership.getConversationId())
                    .orElse(null);
            if (conversation == null) {
                return null;
            }
            List<ConversationMember> members = memberRepository
                    .findByConversationId(conversation.getId());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", conversation.getId());
            row.put("type", conversation.getType());
            row.put("name", titleFor(conversation, members, userId));
            row.put("memberCount", members.size());
            row.put("members", members.stream().map(m -> Map.of(
                    "userId", m.getUserId(),
                    "userName", String.valueOf(m.getUserName()),
                    "userRole", String.valueOf(m.getUserRole()))).collect(Collectors.toList()));
            row.put("lastMessage", conversation.getLastMessagePreview());
            row.put("lastMessageSender", conversation.getLastMessageSender());
            row.put("lastMessageAt", conversation.getLastMessageAt());
            row.put("unread", unreadFor(membership));
            return row;
        }).filter(Objects::nonNull).collect(Collectors.toList());

        rows.sort((a, b) -> {
            LocalDateTime x = (LocalDateTime) a.get("lastMessageAt");
            LocalDateTime y = (LocalDateTime) b.get("lastMessageAt");
            if (x == null && y == null) return 0;
            if (x == null) return 1;
            if (y == null) return -1;
            return y.compareTo(x);
        });

        long totalUnread = rows.stream().mapToLong(r -> (long) r.get("unread")).sum();
        return ResponseEntity.ok(Map.of("conversations", rows, "totalUnread", totalUnread));
    }

    /**
     * Creates a direct chat or a named group.
     *
     * <p>Direct chats are deduplicated: asking for one that already exists returns
     * the existing thread instead of a second empty copy of it.
     */
    @PostMapping("/conversations")
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String type = Optional.ofNullable(text(body, "type")).orElse("DIRECT").toUpperCase();
        String creatorId = text(body, "createdBy");
        String creatorName = text(body, "createdByName");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawMembers = (List<Map<String, Object>>) body.get("members");
        if (creatorId == null || rawMembers == null || rawMembers.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "createdBy and at least one member are required"));
        }

        List<Map<String, Object>> participants = new ArrayList<>(rawMembers);
        if (participants.stream().noneMatch(m -> creatorId.equals(String.valueOf(m.get("userId"))))) {
            participants.add(Map.of("userId", creatorId, "userName", String.valueOf(creatorName),
                    "userRole", String.valueOf(text(body, "createdByRole"))));
        }

        if ("DIRECT".equals(type)) {
            if (participants.size() != 2) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "A direct chat needs exactly two people"));
            }
            String peerId = participants.stream()
                    .map(m -> String.valueOf(m.get("userId")))
                    .filter(id -> !id.equals(creatorId))
                    .findFirst().orElse(null);
            Conversation existing = findDirectBetween(creatorId, peerId);
            if (existing != null) {
                return ResponseEntity.ok(Map.of("id", existing.getId(), "reused", true));
            }
        } else if (participants.size() < 2) {
            // Two is a group when it is named and can grow. Requiring three meant a
            // pair could only ever talk in an unnamed direct thread that nobody
            // else could be added to.
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Pick at least one colleague to start a group"));
        }

        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID().toString());
        conversation.setType(type);
        conversation.setName("GROUP".equals(type) ? text(body, "name") : null);
        conversation.setCreatedBy(creatorId);
        conversation.setCreatedByName(creatorName);
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setLastMessageAt(LocalDateTime.now());
        conversationRepository.save(conversation);

        for (Map<String, Object> participant : participants) {
            addMember(conversation.getId(), String.valueOf(participant.get("userId")),
                    String.valueOf(participant.get("userName")),
                    String.valueOf(participant.get("userRole")));
        }

        if ("GROUP".equals(type)) {
            system(conversation, String.format("%s created the group", creatorName));
        }

        List<String> memberIds = participants.stream()
                .map(m -> String.valueOf(m.get("userId"))).collect(Collectors.toList());
        liveHub.publish(memberIds, "conversations", Map.of("reason", "created"));

        log.info("{} conversation {} created by {}", type, conversation.getId(), creatorName);
        return ResponseEntity.ok(Map.of("id", conversation.getId(), "reused", false));
    }

    /** Adds someone to an existing group. */
    @PostMapping("/conversations/{id}/members")
    @Transactional
    public ResponseEntity<?> addMember(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Conversation conversation = conversationRepository.findById(id).orElse(null);
        if (conversation == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Conversation not found"));
        }
        if (!"GROUP".equalsIgnoreCase(conversation.getType())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Only a group can take new members"));
        }

        String userId = text(body, "userId");
        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
        }
        if (memberRepository.findByConversationIdAndUserId(id, userId).isPresent()) {
            return ResponseEntity.ok(Map.of("status", "already a member"));
        }

        String userName = text(body, "userName");
        addMember(id, userId, userName, text(body, "userRole"));
        system(conversation, String.format("%s joined the group", userName));

        liveHub.publish(memberIds(id), "conversations", Map.of("reason", "member-added"));
        return ResponseEntity.ok(Map.of("status", "added"));
    }

    @DeleteMapping("/conversations/{id}/members/{userId}")
    @Transactional
    public ResponseEntity<?> removeMember(@PathVariable String id, @PathVariable String userId) {
        Conversation conversation = conversationRepository.findById(id).orElse(null);
        if (conversation == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Conversation not found"));
        }
        Optional<ConversationMember> member = memberRepository.findByConversationIdAndUserId(id, userId);
        if (member.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "not a member"));
        }

        List<String> before = memberIds(id);
        memberRepository.delete(member.get());
        system(conversation, String.format("%s left the group", member.get().getUserName()));
        liveHub.publish(before, "conversations", Map.of("reason", "member-removed"));
        return ResponseEntity.ok(Map.of("status", "removed"));
    }

    // ------------------------------------------------------------------ messages

    @GetMapping("/conversations/{id}/messages")
    @Transactional
    public ResponseEntity<?> messages(@PathVariable String id, @RequestParam("userId") String userId) {
        Optional<ConversationMember> membership = memberRepository.findByConversationIdAndUserId(id, userId);
        if (membership.isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "You are not in this conversation"));
        }

        // Opening the thread is what marks it read.
        ConversationMember member = membership.get();
        member.setLastReadAt(LocalDateTime.now());
        memberRepository.save(member);

        return ResponseEntity.ok(Map.of(
                "conversationId", id,
                "messages", messageRepository.findByConversationIdOrderBySentAtAsc(id)));
    }

    @PostMapping("/conversations/{id}/messages")
    @Transactional
    public ResponseEntity<?> send(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String senderId = text(body, "senderId");
        String content = text(body, "body");
        if (senderId == null || content == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "senderId and body are required"));
        }

        Conversation conversation = conversationRepository.findById(id).orElse(null);
        if (conversation == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Conversation not found"));
        }
        if (memberRepository.findByConversationIdAndUserId(id, senderId).isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "You are not in this conversation"));
        }

        Message message = new Message();
        message.setId(UUID.randomUUID().toString());
        message.setConversationId(id);
        message.setSenderId(senderId);
        message.setSenderName(text(body, "senderName"));
        message.setBody(content);
        message.setSentAt(LocalDateTime.now());
        message.setKind("USER");
        messageRepository.save(message);

        touch(conversation, message);

        // Push to everyone in the room; the sender's own copy keeps their tabs in step.
        liveHub.publish(memberIds(id), "message", message);
        return ResponseEntity.ok(message);
    }

    /**
     * Sends a photo, a document or a recorded voice note into a conversation.
     * The message body carries a short caption; the file rides alongside it.
     */
    @PostMapping("/conversations/{id}/attachments")
    @Transactional
    public ResponseEntity<?> sendAttachment(@PathVariable String id,
                                            @RequestParam("file") MultipartFile file,
                                            @RequestParam("senderId") String senderId,
                                            @RequestParam(value = "senderName", required = false) String senderName,
                                            @RequestParam(value = "caption", required = false) String caption) {
        Conversation conversation = conversationRepository.findById(id).orElse(null);
        if (conversation == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Conversation not found"));
        }
        if (memberRepository.findByConversationIdAndUserId(id, senderId).isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "You are not in this conversation"));
        }

        Message message = new Message();
        message.setId(UUID.randomUUID().toString());
        message.setConversationId(id);
        message.setSenderId(senderId);
        message.setSenderName(senderName);
        message.setSentAt(LocalDateTime.now());
        message.setKind("USER");

        try {
            Attachment attachment = fileStore.store(file, "MESSAGE", message.getId(), senderId, senderName);
            message.setBody(caption != null && !caption.isBlank() ? caption : attachment.getFileName());
            message.setAttachmentId(attachment.getId());
            message.setAttachmentKind(attachment.getKind());
            message.setAttachmentName(attachment.getFileName());
            messageRepository.save(message);
            touch(conversation, message);

            liveHub.publish(memberIds(id), "message", message);
            return ResponseEntity.ok(message);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/unread")
    public ResponseEntity<Map<String, Object>> unread(@RequestParam("userId") String userId) {
        long total = memberRepository.findByUserId(userId).stream()
                .mapToLong(this::unreadFor).sum();
        return ResponseEntity.ok(Map.of("userId", userId, "unread", total,
                "openStreams", liveHub.openStreams()));
    }

    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("Chat is running (" + liveHub.openStreams() + " live stream(s))");
    }

    // ------------------------------------------------------------------ helpers

    private Conversation findDirectBetween(String a, String b) {
        for (ConversationMember membership : memberRepository.findByUserId(a)) {
            Conversation conversation = conversationRepository.findById(membership.getConversationId())
                    .orElse(null);
            if (conversation == null || !"DIRECT".equalsIgnoreCase(conversation.getType())) {
                continue;
            }
            if (memberRepository.findByConversationIdAndUserId(conversation.getId(), b).isPresent()) {
                return conversation;
            }
        }
        return null;
    }

    private void addMember(String conversationId, String userId, String userName, String userRole) {
        ConversationMember member = new ConversationMember();
        member.setId(UUID.randomUUID().toString());
        member.setConversationId(conversationId);
        member.setUserId(userId);
        member.setUserName(userName);
        member.setUserRole(userRole);
        member.setJoinedAt(LocalDateTime.now());
        memberRepository.save(member);
    }

    private List<String> memberIds(String conversationId) {
        return memberRepository.findByConversationId(conversationId).stream()
                .map(ConversationMember::getUserId).collect(Collectors.toList());
    }

    private long unreadFor(ConversationMember member) {
        return member.getLastReadAt() == null
                ? messageRepository.countByConversationIdAndSenderIdNot(
                        member.getConversationId(), member.getUserId())
                : messageRepository.countByConversationIdAndSentAtAfterAndSenderIdNot(
                        member.getConversationId(), member.getLastReadAt(), member.getUserId());
    }

    private void touch(Conversation conversation, Message message) {
        conversation.setLastMessageAt(message.getSentAt());
        conversation.setLastMessagePreview(
                message.getBody().length() > 120 ? message.getBody().substring(0, 120) : message.getBody());
        conversation.setLastMessageSender(message.getSenderName());
        conversationRepository.save(conversation);
    }

    private void system(Conversation conversation, String text) {
        Message message = new Message();
        message.setId(UUID.randomUUID().toString());
        message.setConversationId(conversation.getId());
        message.setSenderId("system");
        message.setSenderName("System");
        message.setBody(text);
        message.setSentAt(LocalDateTime.now());
        message.setKind("SYSTEM");
        messageRepository.save(message);
        touch(conversation, message);
    }

    /** Direct chats are titled by the other participant, groups by their name. */
    private static String titleFor(Conversation conversation, List<ConversationMember> members, String viewerId) {
        if ("GROUP".equalsIgnoreCase(conversation.getType())) {
            return conversation.getName() != null ? conversation.getName() : "Group";
        }
        return members.stream()
                .filter(m -> !m.getUserId().equals(viewerId))
                .map(ConversationMember::getUserName)
                .findFirst().orElse("Conversation");
    }

    private static String text(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() || "null".equals(value) ? null : value;
    }
}
