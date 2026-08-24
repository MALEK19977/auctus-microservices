package com.auctus.controller;

import com.auctus.entity.Appointment;
import com.auctus.repository.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202"})
@RestController
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
@Slf4j
public class AppointmentController {

    private final AppointmentRepository appointmentRepository;

    /**
     * Appointments for one agent, or for everyone when {@code ownerId} is omitted -
     * which is how an administrator sees the whole branch's diary.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "ownerId", required = false) String ownerId,
            @RequestParam(value = "days", defaultValue = "30") int days) {

        LocalDateTime from = LocalDateTime.now().minusDays(1);
        LocalDateTime to = LocalDateTime.now().plusDays(days);

        List<Appointment> items = (ownerId == null || ownerId.isBlank() || "ALL".equals(ownerId))
                ? appointmentRepository.findByStartsAtBetweenOrderByStartsAtAsc(from, to)
                : appointmentRepository.findByOwnerIdAndStartsAtBetweenOrderByStartsAtAsc(ownerId, from, to);

        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> enriched = items.stream().map(a -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("appointment", a);
            row.put("minutesUntil", Duration.between(now, a.getStartsAt()).toMinutes());
            // A reminder is due once the lead time is reached and the slot is still ahead.
            row.put("reminderDue", isReminderDue(a, now));
            return row;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "count", enriched.size(),
                "items", enriched,
                "dueReminders", enriched.stream().filter(r -> (boolean) r.get("reminderDue")).count()));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String title = text(body, "title");
        LocalDateTime startsAt = time(body, "startsAt");

        if (title == null || startsAt == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "title and startsAt (ISO date-time) are required"));
        }

        LocalDateTime endsAt = time(body, "endsAt");
        if (endsAt != null && endsAt.isBefore(startsAt)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "endsAt cannot be before startsAt"));
        }

        Appointment appointment = new Appointment();
        appointment.setId(UUID.randomUUID().toString());
        appointment.setOwnerId(text(body, "ownerId"));
        appointment.setOwnerName(text(body, "ownerName"));
        appointment.setTitle(title);
        appointment.setDescription(text(body, "description"));
        appointment.setClientRib(text(body, "clientRib"));
        appointment.setClientName(text(body, "clientName"));
        appointment.setLocation(text(body, "location"));
        appointment.setStartsAt(startsAt);
        appointment.setEndsAt(endsAt != null ? endsAt : startsAt.plusMinutes(30));
        appointment.setStatus("SCHEDULED");
        appointment.setReminderMinutes(number(body, "reminderMinutes", 30));
        appointment.setCreatedBy(text(body, "createdBy"));
        appointment.setCreatedByName(text(body, "createdByName"));
        appointment.setCreatedAt(LocalDateTime.now());
        appointment.setUpdatedAt(LocalDateTime.now());
        appointmentRepository.save(appointment);

        log.info("Appointment '{}' for {} at {}", title, appointment.getOwnerName(), startsAt);
        return ResponseEntity.ok(appointment);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return appointmentRepository.findById(id).<ResponseEntity<?>>map(appointment -> {
            Optional.ofNullable(text(body, "title")).ifPresent(appointment::setTitle);
            Optional.ofNullable(text(body, "description")).ifPresent(appointment::setDescription);
            Optional.ofNullable(text(body, "location")).ifPresent(appointment::setLocation);
            Optional.ofNullable(text(body, "status")).map(String::toUpperCase).ifPresent(appointment::setStatus);
            Optional.ofNullable(time(body, "startsAt")).ifPresent(appointment::setStartsAt);
            Optional.ofNullable(time(body, "endsAt")).ifPresent(appointment::setEndsAt);
            appointment.setUpdatedAt(LocalDateTime.now());
            appointmentRepository.save(appointment);
            return ResponseEntity.ok(appointment);
        }).orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Appointment not found")));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> cancel(@PathVariable String id) {
        return appointmentRepository.findById(id).<ResponseEntity<?>>map(appointment -> {
            // Cancelled rather than deleted: the diary stays auditable.
            appointment.setStatus("CANCELLED");
            appointment.setUpdatedAt(LocalDateTime.now());
            appointmentRepository.save(appointment);
            return ResponseEntity.ok(appointment);
        }).orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Appointment not found")));
    }

    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("Collab Service (appointments) is running!");
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isReminderDue(Appointment a, LocalDateTime now) {
        if (!"SCHEDULED".equalsIgnoreCase(a.getStatus()) || a.getStartsAt() == null) {
            return false;
        }
        int lead = a.getReminderMinutes() == null ? 30 : a.getReminderMinutes();
        LocalDateTime dueFrom = a.getStartsAt().minusMinutes(lead);
        return !now.isBefore(dueFrom) && now.isBefore(a.getStartsAt());
    }

    private static String text(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    private static Integer number(Map<String, Object> body, String key, int fallback) {
        Object raw = body.get(key);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return raw == null ? fallback : Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static LocalDateTime time(Map<String, Object> body, String key) {
        String raw = text(body, key);
        if (raw == null) {
            return null;
        }
        try {
            // Accepts both "2026-08-11T14:30" and "2026-08-11T14:30:00".
            return LocalDateTime.parse(raw.length() == 16 ? raw + ":00" : raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
