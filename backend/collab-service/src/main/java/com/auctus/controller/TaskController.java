package com.auctus.controller;

import com.auctus.entity.Attachment;
import com.auctus.entity.Task;
import com.auctus.repository.AttachmentRepository;
import com.auctus.repository.TaskRepository;
import com.auctus.service.FileStore;
import com.auctus.service.LiveHub;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202"})
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
@Slf4j
public class TaskController {

    private final TaskRepository taskRepository;
    private final AttachmentRepository attachmentRepository;
    private final FileStore fileStore;
    private final LiveHub liveHub;

    /**
     * The work queue. Without {@code assignedTo} this returns everything, which is
     * how an administrator watches the whole branch.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "assignedTo", required = false) String assignedTo,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "clientRib", required = false) String clientRib) {

        List<Task> tasks;
        if (clientRib != null && !clientRib.isBlank()) {
            tasks = taskRepository.findByClientRibOrderByCreatedAtDesc(clientRib);
        } else if (assignedTo != null && !assignedTo.isBlank() && !"ALL".equals(assignedTo)) {
            tasks = taskRepository.findByAssignedToIdOrderByDueAtAsc(assignedTo);
        } else {
            tasks = taskRepository.findAll();
        }

        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            tasks = tasks.stream()
                    .filter(t -> status.equalsIgnoreCase(t.getStatus()))
                    .collect(Collectors.toList());
        }

        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> rows = tasks.stream().map(task -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task", task);
            row.put("attachments", fileStore.listFor("TASK", task.getId()));
            row.put("overdue", task.getDueAt() != null
                    && task.getDueAt().isBefore(now)
                    && !"DONE".equalsIgnoreCase(task.getStatus())
                    && !"CANCELLED".equalsIgnoreCase(task.getStatus()));
            return row;
        }).collect(Collectors.toList());

        Map<String, Object> counts = new LinkedHashMap<>();
        for (String state : List.of("PENDING", "IN_PROGRESS", "DONE", "CANCELLED")) {
            counts.put(state, tasks.stream().filter(t -> state.equalsIgnoreCase(t.getStatus())).count());
        }

        return ResponseEntity.ok(Map.of("count", rows.size(), "items", rows, "counts", counts));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> one(@PathVariable String id) {
        return taskRepository.findById(id)
                .<ResponseEntity<?>>map(task -> ResponseEntity.ok(Map.of(
                        "task", task,
                        "attachments", fileStore.listFor("TASK", task.getId()))))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Task not found")));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String title = text(body, "title");
        String assignedToId = text(body, "assignedToId");

        if (title == null || assignedToId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "A title and someone to assign it to are required"));
        }

        Task task = new Task();
        task.setId(UUID.randomUUID().toString());
        task.setTitle(title);
        task.setDescription(text(body, "description"));
        task.setCategory(Optional.ofNullable(text(body, "category")).orElse("OTHER"));
        task.setClientRib(text(body, "clientRib"));
        task.setClientName(text(body, "clientName"));
        task.setChequeId(text(body, "chequeId"));
        task.setChequeNumber(text(body, "chequeNumber"));
        task.setAssignedToId(assignedToId);
        task.setAssignedToName(text(body, "assignedToName"));
        task.setCreatedById(text(body, "createdById"));
        task.setCreatedByName(text(body, "createdByName"));
        task.setCreatedByRole(text(body, "createdByRole"));
        task.setStartsAt(time(body, "startsAt"));
        task.setDueAt(time(body, "dueAt"));
        task.setStatus("PENDING");
        task.setPriority(Optional.ofNullable(text(body, "priority")).orElse("NORMAL"));
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskRepository.save(task);

        // The assignee finds out immediately, on the stream they already hold open.
        liveHub.publish(List.of(assignedToId), "task", Map.of(
                "reason", "assigned", "taskId", task.getId(), "title", task.getTitle()));

        log.info("Task '{}' assigned to {} by {}", title, task.getAssignedToName(), task.getCreatedByName());
        return ResponseEntity.ok(task);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return taskRepository.findById(id).<ResponseEntity<?>>map(task -> {
            Optional.ofNullable(text(body, "title")).ifPresent(task::setTitle);
            Optional.ofNullable(text(body, "description")).ifPresent(task::setDescription);
            Optional.ofNullable(text(body, "priority")).map(String::toUpperCase).ifPresent(task::setPriority);
            Optional.ofNullable(text(body, "category")).map(String::toUpperCase).ifPresent(task::setCategory);
            Optional.ofNullable(text(body, "resolutionNote")).ifPresent(task::setResolutionNote);
            Optional.ofNullable(time(body, "startsAt")).ifPresent(task::setStartsAt);
            Optional.ofNullable(time(body, "dueAt")).ifPresent(task::setDueAt);

            // Reassignment tells both the old and the new owner.
            String newAssignee = text(body, "assignedToId");
            String previousAssignee = task.getAssignedToId();
            if (newAssignee != null && !newAssignee.equals(previousAssignee)) {
                task.setAssignedToId(newAssignee);
                task.setAssignedToName(text(body, "assignedToName"));
                liveHub.publish(Arrays.asList(previousAssignee, newAssignee), "task",
                        Map.of("reason", "reassigned", "taskId", task.getId()));
            }

            String status = text(body, "status");
            if (status != null) {
                task.setStatus(status.toUpperCase());
                if ("DONE".equalsIgnoreCase(status)) {
                    task.setCompletedAt(LocalDateTime.now());
                }
                liveHub.publish(participants(task), "task",
                        Map.of("reason", "status", "taskId", task.getId(), "status", task.getStatus()));
            }

            task.setUpdatedAt(LocalDateTime.now());
            taskRepository.save(task);
            return ResponseEntity.ok(task);
        }).orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Task not found")));
    }

    /** Attach a document, photo or scan to a task. */
    @PostMapping("/{id}/attachments")
    public ResponseEntity<?> attach(@PathVariable String id,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "uploaderId", required = false) String uploaderId,
                                    @RequestParam(value = "uploaderName", required = false) String uploaderName) {
        Optional<Task> task = taskRepository.findById(id);
        if (task.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Task not found"));
        }
        try {
            Attachment attachment = fileStore.store(file, "TASK", id, uploaderId, uploaderName);
            liveHub.publish(participants(task.get()), "task",
                    Map.of("reason", "attachment", "taskId", id));
            return ResponseEntity.ok(attachment);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** How many tasks are still waiting on this person - the dashboard badge. */
    @GetMapping("/pending-count")
    public ResponseEntity<Map<String, Object>> pendingCount(@RequestParam("userId") String userId) {
        long pending = taskRepository.countByAssignedToIdAndStatus(userId, "PENDING");
        long inProgress = taskRepository.countByAssignedToIdAndStatus(userId, "IN_PROGRESS");
        return ResponseEntity.ok(Map.of(
                "userId", userId, "pending", pending, "inProgress", inProgress,
                "open", pending + inProgress));
    }

    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("Tasks are running!");
    }

    private static List<String> participants(Task task) {
        List<String> people = new ArrayList<>();
        if (task.getAssignedToId() != null) people.add(task.getAssignedToId());
        if (task.getCreatedById() != null && !task.getCreatedById().equals(task.getAssignedToId())) {
            people.add(task.getCreatedById());
        }
        return people;
    }

    private static String text(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() || "null".equals(value) ? null : value;
    }

    private static LocalDateTime time(Map<String, Object> body, String key) {
        String raw = text(body, key);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.length() == 16 ? raw + ":00" : raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
