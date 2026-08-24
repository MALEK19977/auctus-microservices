package com.auctus.repository;

import com.auctus.entity.Cheque;
import com.auctus.entity.ChequeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChequeRepository extends JpaRepository<Cheque, String> {
    List<Cheque> findTop5ByOrderByCreatedAtDesc();
    List<Cheque> findByValidatedByOrderByCreatedAtDesc(String validatedBy);
    List<Cheque> findByStatusOrderByCreatedAtDesc(ChequeStatus status);

    /** Cheques drawn on this account - the link between a client and their cheques. */
    List<Cheque> findByIssuerRibOrderByValidatedAtDesc(String issuerRib);
}