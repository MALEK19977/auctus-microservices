package com.auctus.repository;

import com.auctus.entity.ChequeBookRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChequeBookRequestRepository extends JpaRepository<ChequeBookRequest, String> {

    List<ChequeBookRequest> findByClientIdOrderByRequestedAtDesc(String clientId);
}
