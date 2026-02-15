package com.example.ai.controller;

import com.example.ai.dto.DocumentResponse;
import com.example.ai.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/documents")
    public DocumentResponse uploadDocument(@RequestParam("file") MultipartFile file) {
        log.info("POST /api/documents - 파일: {}, 크기: {}KB, 타입: {}",
                file.getOriginalFilename(),
                file.getSize() / 1024,
                file.getContentType());

        int chunks = documentService.processDocument(file.getResource());

        String message = chunks > 0 ? "문서 처리가 완료되었습니다." : "문서에서 텍스트를 추출할 수 없습니다.";
        log.info("POST /api/documents - 응답: {} ({}개 청크)", message, chunks);

        return new DocumentResponse(file.getOriginalFilename(), chunks, message);
    }
}
