package com.example.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatClient;

    public String chat(String message) {
        log.info("========== 일반 채팅 요청 ==========");
        log.info("사용자 메시지: {}", message);

        try {
            String answer = chatClient.prompt()
                    .user(message)
                    .call()
                    .content();
            log.info("일반 채팅 응답 길이: {}자", answer != null ? answer.length() : 0);
            return answer;
        } catch (Exception e) {
            log.error("일반 채팅 오류: {}", e.getMessage(), e);
            throw e;
        }
    }
}
