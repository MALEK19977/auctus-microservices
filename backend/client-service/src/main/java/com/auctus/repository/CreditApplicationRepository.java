package com.auctus.repository;

import com.auctus.entity.CreditApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditApplicationRepository extends JpaRepository<CreditApplication, String> {

    List<CreditApplication> findByClientIdOrderByCreatedAtDesc(String clientId);

    List<CreditApplication> findByStatusOrderByCreatedAtDesc(String status);
}
