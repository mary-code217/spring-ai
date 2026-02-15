package com.example.ai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private final VectorStore vectorStore;

    public int processDocument(Resource resource) {
        // 1. 문서 읽기 (Tika가 PDF, DOCX, PPTX, HTML 등 자동 감지)
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> documents = reader.get();

        // 2. 청킹 (기본: 800 토큰, 오버랩 400 토큰)
        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(documents);

        // 3. 임베딩 + 벡터 저장소에 추가
        vectorStore.add(chunks);

        return chunks.size();
    }
}
