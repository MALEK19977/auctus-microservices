package com.auctus.repository;

import com.auctus.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, String> {

    List<Attachment> findByOwnerTypeAndOwnerIdOrderByUploadedAtAsc(String ownerType, String ownerId);
}
