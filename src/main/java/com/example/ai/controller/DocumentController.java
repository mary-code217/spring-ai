package com.example.ai.controller;

import com.example.ai.dto.DocumentResponse;
import com.example.ai.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/documents")
    public DocumentResponse uploadDocument(@RequestParam("file") MultipartFile file) {
        int chunks = documentService.processDocument(file.getResource());
        return new DocumentResponse(
                file.getOriginalFilename(),
                chunks,
                "문서 처리가 완료되었습니다."
        );
    }
}
