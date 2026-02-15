package com.example.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final VectorStore vectorStore;

    public int processDocument(Resource resource) {
        log.info("========== 문서 처리 시작 ==========");
        log.info("파일명: {}", resource.getFilename());

        // 1. 문서 읽기
        log.info("[1단계] Tika 문서 읽기 시작...");
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> documents = reader.get();
        log.info("[1단계] Tika 문서 읽기 완료: {}개 문서 추출", documents.size());

        if (documents.isEmpty()) {
            log.error("[1단계 실패] Tika가 문서에서 텍스트를 추출하지 못했습니다.");
            return 0;
        }

        for (int i = 0; i < documents.size(); i++) {
            String content = documents.get(i).getText();
            log.info("[1단계] 문서[{}] 텍스트 길이: {}자", i, content.length());
            log.info("[1단계] 문서[{}] 내용 미리보기: {}", i,
                    content.substring(0, Math.min(300, content.length())));
        }

        // 2. 청킹
        log.info("[2단계] 텍스트 청킹 시작...");
        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(documents);
        log.info("[2단계] 청킹 완료: {}개 청크 생성", chunks.size());

        if (chunks.isEmpty()) {
            log.error("[2단계 실패] 청킹 결과가 비어있습니다. 문서 텍스트가 너무 짧을 수 있습니다.");
            return 0;
        }

        for (int i = 0; i < chunks.size(); i++) {
            log.info("[2단계] 청크[{}] 길이: {}자", i, chunks.get(i).getText().length());
        }

        // 3. 임베딩 + 벡터 저장소에 추가
        log.info("[3단계] 임베딩 및 벡터 저장소 저장 시작...");
        try {
            vectorStore.add(chunks);
            log.info("[3단계] 벡터 저장소에 {}개 청크 저장 성공", chunks.size());
        } catch (Exception e) {
            log.error("[3단계 실패] 벡터 저장소 저장 중 오류: {}", e.getMessage(), e);
            throw e;
        }

        log.info("========== 문서 처리 완료 (총 {}개 청크) ==========", chunks.size());
        return chunks.size();
    }
}
