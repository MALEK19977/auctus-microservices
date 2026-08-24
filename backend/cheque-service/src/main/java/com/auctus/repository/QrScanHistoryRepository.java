package com.auctus.repository;

import com.auctus.entity.QrScanHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface QrScanHistoryRepository extends JpaRepository<QrScanHistory, Long> {
    List<QrScanHistory> findByOrderByScanDateDesc();
    List<QrScanHistory> findByScannedByOrderByScanDateDesc(String scannedBy);
    List<QrScanHistory> findByChequeNumber(String chequeNumber);
}