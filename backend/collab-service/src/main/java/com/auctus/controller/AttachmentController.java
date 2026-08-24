package com.auctus.controller;

import com.auctus.entity.Attachment;
import com.auctus.repository.AttachmentRepository;
import com.auctus.service.FileStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202"})
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentRepository attachmentRepository;
    private final FileStore fileStore;

    /**
     * Serves the file. Images and audio render inline so a photo or a voice note
     * plays in place; anything else downloads under its original name.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> download(@PathVariable String id) {
        Attachment attachment = attachmentRepository.findById(id).orElse(null);
        if (attachment == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Attachment not found"));
        }

        return fileStore.read(attachment).<ResponseEntity<?>>map(bytes -> {
            boolean inline = "IMAGE".equals(attachment.getKind()) || "AUDIO".equals(attachment.getKind());
            String disposition = (inline ? "inline" : "attachment")
                    + "; filename=\"" + attachment.getFileName().replace("\"", "") + "\"";
            MediaType type = attachment.getContentType() == null
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(attachment.getContentType());
            return ResponseEntity.ok()
                    .contentType(type)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .body(bytes);
        }).orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "File missing on disk")));
    }
}
