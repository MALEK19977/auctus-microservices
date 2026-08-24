package com.auctus.repository;

import com.auctus.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, String> {

    List<Task> findByAssignedToIdOrderByDueAtAsc(String assignedToId);

    List<Task> findByAssignedToIdAndStatusOrderByDueAtAsc(String assignedToId, String status);

    List<Task> findByClientRibOrderByCreatedAtDesc(String clientRib);

    long countByAssignedToIdAndStatus(String assignedToId, String status);
}
