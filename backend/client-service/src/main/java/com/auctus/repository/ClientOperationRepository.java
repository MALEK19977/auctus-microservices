package com.auctus.repository;

import com.auctus.entity.ClientOperation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClientOperationRepository extends JpaRepository<ClientOperation, String> {

    List<ClientOperation> findByClientIdOrderByPerformedAtDesc(String clientId);
}
