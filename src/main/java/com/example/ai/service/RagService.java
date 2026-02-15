package com.example.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RagService {

    private final ChatClient ragChatClient;

    public RagService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        this.ragChatClient = chatClientBuilder
                .defaultSystem("당신은 제공된 문서 컨텍스트를 기반으로 정확하게 답변하는 한국어 AI 어시스턴트입니다. "
                        + "컨텍스트에 관련 정보가 없으면 '제공된 문서에서 해당 정보를 찾을 수 없습니다.'라고 답변하세요.")
                .defaultAdvisors(
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(SearchRequest.builder()
                                        .similarityThreshold(0.3)
                                        .topK(5)
                                        .build())
                                .build())
                .build();
        log.info("RAG ChatClient 초기화 완료 (similarityThreshold=0.3, topK=5)");
    }

    public String chat(String message) {
        log.info("========== RAG 채팅 요청 ==========");
        log.info("사용자 질문: {}", message);

        try {
            String answer = ragChatClient.prompt()
                    .user(message)
                    .call()
                    .content();
            log.info("RAG 응답 길이: {}자", answer != null ? answer.length() : 0);
            log.info("RAG 응답 미리보기: {}", answer != null ?
                    answer.substring(0, Math.min(200, answer.length())) : "null");
            return answer;
        } catch (Exception e) {
            log.error("RAG 채팅 오류: {}", e.getMessage(), e);
            throw e;
        }
    }
}
