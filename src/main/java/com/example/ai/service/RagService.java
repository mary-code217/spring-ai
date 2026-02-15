package com.example.ai.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

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
                                        .similarityThreshold(0.7)
                                        .topK(5)
                                        .build())
                                .build())
                .build();
    }

    public String chat(String message) {
        return ragChatClient.prompt()
                .user(message)
                .call()
                .content();
    }
}
