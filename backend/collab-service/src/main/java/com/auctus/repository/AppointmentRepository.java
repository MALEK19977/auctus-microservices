package com.auctus.repository;

import com.auctus.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AppointmentRepository extends JpaRepository<Appointment, String> {

    List<Appointment> findByOwnerIdOrderByStartsAtAsc(String ownerId);

    List<Appointment> findByOwnerIdAndStartsAtBetweenOrderByStartsAtAsc(
            String ownerId, LocalDateTime from, LocalDateTime to);

    List<Appointment> findByStartsAtBetweenOrderByStartsAtAsc(LocalDateTime from, LocalDateTime to);
}
