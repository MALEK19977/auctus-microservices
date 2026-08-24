package com.auctus.service;

import com.auctus.entity.Attachment;
import com.auctus.repository.AttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores uploaded files on disk and their metadata in the database.
 *
 * <p>Files are written under a name the server chooses; the name the user gave is
 * only ever shown, never used to build a path. That keeps an upload called
 * "../../etc/passwd" from escaping the folder.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileStore {

    private static final long MAX_BYTES = 15L * 1024 * 1024;

    private final AttachmentRepository attachmentRepository;

    @Value("${collab.upload-dir}")
    private String uploadDir;

    public Attachment store(MultipartFile file, String ownerType, String ownerId,
                            String uploaderId, String uploaderName) throws IOException {
        if (file.isEmpty()) {
            throw new IOException("The file is empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IOException("File exceeds the 15 MB limit");
        }

        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(root);

        // Never hand the uploaded name to Paths.get: on Windows a colon (which a
        // timestamped name easily contains) throws InvalidPathException and the
        // whole upload fails. Strip it to a bare basename with plain string work.
        String original = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String baseName = original.replaceAll("^.*[/\\\\]", "");
        String safeName = baseName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.isBlank()) {
            safeName = "upload";
        }

        String extension = safeName.contains(".")
                ? safeName.substring(safeName.lastIndexOf('.')).replaceAll("[^A-Za-z0-9.]", "")
                : "";
        String storedName = UUID.randomUUID() + extension;

        Path target = root.resolve(storedName).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Rejected upload path");
        }
        file.transferTo(target);

        Attachment attachment = new Attachment();
        attachment.setId(UUID.randomUUID().toString());
        attachment.setOwnerType(ownerType);
        attachment.setOwnerId(ownerId);
        attachment.setFileName(safeName);
        attachment.setContentType(file.getContentType());
        attachment.setSizeBytes(file.getSize());
        attachment.setKind(classify(file.getContentType()));
        attachment.setStoredName(storedName);
        attachment.setUploadedById(uploaderId);
        attachment.setUploadedByName(uploaderName);
        attachment.setUploadedAt(LocalDateTime.now());
        attachmentRepository.save(attachment);

        log.info("Stored {} ({} bytes) for {} {}", attachment.getFileName(),
                attachment.getSizeBytes(), ownerType, ownerId);
        return attachment;
    }

    public Optional<byte[]> read(Attachment attachment) {
        try {
            Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path file = root.resolve(attachment.getStoredName()).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            log.error("Could not read {}: {}", attachment.getStoredName(), e.getMessage());
            return Optional.empty();
        }
    }

    public List<Attachment> listFor(String ownerType, String ownerId) {
        return attachmentRepository.findByOwnerTypeAndOwnerIdOrderByUploadedAtAsc(ownerType, ownerId);
    }

    private static String classify(String contentType) {
        if (contentType == null) {
            return "DOCUMENT";
        }
        if (contentType.startsWith("image/")) {
            return "IMAGE";
        }
        if (contentType.startsWith("audio/")) {
            return "AUDIO";
        }
        return "DOCUMENT";
    }
}
