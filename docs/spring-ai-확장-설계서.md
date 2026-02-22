# Spring AI 챗봇 확장 구현 계획서 (Stage 4~9)

## 1. 컨텍스트

Stage 1~3에서 기본 Gemini 챗봇 → RAG → PgVector 영속화까지 완료했다.
회사 서비스에 실제 AI 챗봇을 도입하려면 프로토타입 수준을 넘어 **프로덕션 품질**의 기능이 필요하다.
이 문서는 프로토타입 → 프로덕션 전환에 필요한 6개 학습 단계를 설계한다.

**현재 상태:** Stage 3 완료. PgVector 기반 RAG 챗봇 동작 중.

---

## 2. 단계 의존 관계

```
Stage 4 (Streaming) ─────────────────────┐
Stage 5 (Chat Memory) ──────────────────┤
Stage 6 (에러 처리) ────────────────────┤──→ 각각 독립 수행 가능
Stage 7 (프롬프트 엔지니어링) ──────────┤
Stage 9 (Observability) ────────────────┘

Stage 8 (Function Calling) ──→ Stage 4, 5 선행 권장 (Streaming + Memory와 결합 시 효과적)
```

> **추천 순서:** Stage 4 → 5 → 6 → 7 → 8 → 9
> 단, Stage 4~7과 9는 독립적이므로 관심사에 따라 순서 변경 가능.

---

## 3. Stage 4: Streaming 응답 (SSE)

### 목표
`call()` → `stream()` 전환. Server-Sent Events로 토큰 단위 실시간 스트리밍을 구현하여 사용자 체감 응답 속도를 개선한다.

### 학습 포인트
- Spring AI `ChatClient.stream()` API (`Flux<String>`, `Flux<ChatResponse>`)
- Spring MVC + WebFlux 공존 환경에서의 SSE 반환
- 프론트엔드 `fetch` + `ReadableStream` 기반 스트리밍 소비
- `QuestionAnswerAdvisor`(RAG)와 Streaming 결합

### 의존성 추가 — `build.gradle`
```gradle
implementation 'org.springframework.boot:spring-boot-starter-webflux'
```
> Spring MVC와 WebFlux 공존 시, `Flux` 반환 타입을 `text/event-stream`으로 매핑하면 자동으로 SSE 스트리밍된다.

### 생성/수정 파일 (4개 수정)

| 파일 | 변경 내용 |
|------|-----------|
| `service/ChatService.java` | `streamChat()` 메서드 추가 |
| `service/RagService.java` | `streamChat()` 메서드 추가 |
| `controller/ChatController.java` | `POST /api/chat/stream` 엔드포인트 추가 |
| `controller/RagController.java` | `POST /api/rag/chat/stream` 엔드포인트 추가 |
| `static/js/app.js` | `fetch` + `ReadableStream` 기반 스트리밍 수신 |

### 핵심 코드 패턴

**ChatService — Streaming 메서드:**
```java
public Flux<String> streamChat(String message) {
    return chatClient.prompt()
            .user(message)
            .stream()
            .content();
}
```

**RagService — RAG + Streaming:**
```java
public Flux<String> streamChat(String message) {
    return ragChatClient.prompt()
            .user(message)
            .stream()
            .content();
}
```
> `QuestionAnswerAdvisor`는 `StreamAdvisor`를 구현하므로 `.stream()` 호환된다.
> 벡터 검색은 블로킹으로 먼저 수행된 후 LLM 스트리밍이 시작된다.

**Controller — SSE 엔드포인트:**
```java
@PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> chatStream(@RequestBody ChatRequest request) {
    return chatService.streamChat(request.getMessage());
}
```

**Frontend — ReadableStream 소비:**
```javascript
async function sendStreamMessage(message, endpoint) {
    const response = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message })
    });
    const reader = response.body.getReader();
    const decoder = new TextDecoder();

    while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        appendToCurrentMessage(decoder.decode(value, { stream: true }));
    }
}
```

### API 엔드포인트 추가

| Method | Path | 기능 |
|--------|------|------|
| POST | `/api/chat/stream` | 일반 채팅 스트리밍 |
| POST | `/api/rag/chat/stream` | RAG 채팅 스트리밍 |

### 검증
```bash
# SSE 스트리밍 확인 (토큰 단위로 출력되는지)
curl -N -X POST http://localhost:8090/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "Spring AI의 장점을 5가지 알려줘"}'

# RAG 스트리밍
curl -N -X POST http://localhost:8090/api/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "업로드한 문서의 주요 내용을 요약해줘"}'

# 브라우저에서 실시간 타이핑 효과 확인
```

---

## 4. Stage 5: 대화 히스토리 (Chat Memory)

### 목표
멀티턴 대화를 지원하여 "아까 말한 것"처럼 이전 맥락을 이어가는 챗봇을 구현한다.
인메모리 → JDBC(PostgreSQL) 영속화까지 진행한다.

### 학습 포인트
- Spring AI `ChatMemory` 인터페이스와 `MessageWindowChatMemory`
- `MessageChatMemoryAdvisor`를 통한 ChatClient 통합
- `conversationId` 기반 대화 격리
- `InMemoryChatMemoryRepository` → `JdbcChatMemoryRepository` 전환
- 프론트엔드 세션 관리 (conversationId 생성/유지)

### 의존성 추가 — `build.gradle`
```gradle
// JDBC 영속화 시
implementation 'org.springframework.ai:spring-ai-starter-model-chat-memory-repository-jdbc'
```

### 설정 추가 — `application.properties`
```properties
# Chat Memory JDBC 스키마 자동 생성
spring.ai.chat.memory.repository.jdbc.initialize-schema=always
```

### 생성/수정 파일 (5개 수정, 1개 생성)

| 파일 | 변경 내용 |
|------|-----------|
| `config/ChatMemoryConfig.java` | **생성** — `ChatMemory` 빈 설정 |
| `config/ChatClientConfig.java` | `MessageChatMemoryAdvisor` 추가 |
| `service/ChatService.java` | `conversationId` 파라미터 추가 |
| `service/RagService.java` | `conversationId` 파라미터 추가 |
| `controller/ChatController.java` | 요청에서 `conversationId` 수신 |
| `controller/RagController.java` | 요청에서 `conversationId` 수신 |
| `dto/ChatRequest.java` | `conversationId` 필드 추가 |
| `static/js/app.js` | `conversationId` 생성/관리 로직 |

### 핵심 코드 패턴

**ChatMemoryConfig — 메모리 빈 설정:**
```java
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();
    }
}
```
> JDBC 스타터를 추가하면 `JdbcChatMemoryRepository`가 자동 구성된다.
> 스타터 없이 인메모리로 먼저 시작하려면 `InMemoryChatMemoryRepository`를 직접 빈 등록.

**ChatClientConfig — Advisor 적용:**
```java
@Bean
public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
    return builder
            .defaultSystem("당신은 친절한 한국어 AI 어시스턴트입니다.")
            .defaultAdvisors(
                    MessageChatMemoryAdvisor.builder(chatMemory).build()
            )
            .build();
}
```

**Service — conversationId 전달:**
```java
public String chat(String message, String conversationId) {
    return chatClient.prompt()
            .user(message)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .call()
            .content();
}
```

**DTO — conversationId 추가:**
```java
public record ChatRequest(String message, String conversationId) {}
```

**Frontend — 세션 관리:**
```javascript
let conversationId = crypto.randomUUID();

// 새 대화 시작 버튼
function startNewConversation() {
    conversationId = crypto.randomUUID();
    clearChatMessages();
}

// 메시지 전송 시 conversationId 포함
fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, conversationId })
});
```

### 검증
```bash
# 1) 첫 번째 메시지
curl -X POST http://localhost:8090/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "내 이름은 홍길동이야", "conversationId": "test-session-1"}'

# 2) 맥락 이어가기 (같은 conversationId)
curl -X POST http://localhost:8090/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "내 이름이 뭐라고 했지?", "conversationId": "test-session-1"}'
# → "홍길동"이라고 답변해야 정상

# 3) 다른 세션 (맥락 없음)
curl -X POST http://localhost:8090/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "내 이름이 뭐라고 했지?", "conversationId": "test-session-2"}'
# → 모른다고 답변해야 정상

# 4) 앱 재시작 후 기존 conversationId로 테스트 (JDBC 영속화 검증)
```

---

## 5. Stage 6: 에러 처리 및 안정성

### 목표
LLM API 의존 서비스의 장애 상황(타임아웃, Rate Limit, 토큰 초과 등)에 대한 체계적인 에러 처리를 구현한다.

### 학습 포인트
- `@ControllerAdvice` 기반 글로벌 예외 처리
- LLM API 특유의 에러 유형 (Rate Limit, 토큰 초과, 모델 불가용)
- 표준화된 에러 응답 형식
- 프론트엔드 에러 유형별 사용자 메시지

### 의존성 추가
없음 (Spring Boot 내장 기능)

### 생성/수정 파일 (4개 생성, 1개 수정)

| 파일 | 변경 내용 |
|------|-----------|
| `exception/GlobalExceptionHandler.java` | **생성** — `@ControllerAdvice` 글로벌 예외 핸들러 |
| `exception/LlmServiceException.java` | **생성** — LLM 관련 커스텀 예외 |
| `exception/DocumentProcessingException.java` | **생성** — 문서 처리 관련 커스텀 예외 |
| `dto/ErrorResponse.java` | **생성** — 표준 에러 응답 DTO |
| `static/js/app.js` | 에러 코드별 사용자 메시지 분기 |

### 핵심 코드 패턴

**ErrorResponse DTO:**
```java
public record ErrorResponse(
    String code,        // "LLM_TIMEOUT", "RATE_LIMIT", "TOKEN_EXCEEDED" 등
    String message,     // 사용자 친화적 메시지
    LocalDateTime timestamp
) {}
```

**GlobalExceptionHandler:**
```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(LlmServiceException.class)
    public ResponseEntity<ErrorResponse> handleLlmException(LlmServiceException e) {
        log.error("LLM 서비스 오류: {}", e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("LLM_ERROR", "AI 서비스가 일시적으로 불안정합니다.", LocalDateTime.now()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleFileSizeExceeded(MaxUploadSizeExceededException e) {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("FILE_TOO_LARGE", "파일 크기가 제한(50MB)을 초과했습니다.", LocalDateTime.now()));
    }
}
```

**Service에서 예외 래핑:**
```java
public String chat(String message) {
    try {
        return chatClient.prompt().user(message).call().content();
    } catch (Exception e) {
        if (e.getMessage().contains("429") || e.getMessage().contains("RESOURCE_EXHAUSTED")) {
            throw new LlmServiceException("RATE_LIMIT", "API 호출 한도를 초과했습니다.", e);
        }
        throw new LlmServiceException("LLM_ERROR", "AI 응답 생성에 실패했습니다.", e);
    }
}
```

### 에러 유형 분류

| 에러 코드 | HTTP 상태 | 원인 | 사용자 메시지 |
|-----------|----------|------|-------------|
| `LLM_TIMEOUT` | 504 | LLM API 응답 시간 초과 | "응답 시간이 초과되었습니다. 다시 시도해주세요." |
| `RATE_LIMIT` | 429 | API 호출 한도 초과 | "요청이 너무 많습니다. 잠시 후 다시 시도해주세요." |
| `TOKEN_EXCEEDED` | 400 | 입력 토큰 초과 | "메시지가 너무 깁니다. 짧게 줄여주세요." |
| `LLM_ERROR` | 503 | LLM 서비스 일반 오류 | "AI 서비스가 일시적으로 불안정합니다." |
| `FILE_TOO_LARGE` | 413 | 업로드 파일 크기 초과 | "파일 크기가 제한을 초과했습니다." |
| `UNSUPPORTED_FILE` | 400 | 지원하지 않는 파일 형식 | "지원하지 않는 파일 형식입니다." |
| `DOCUMENT_PROCESSING_ERROR` | 500 | 문서 파싱/청킹 실패 | "문서 처리 중 오류가 발생했습니다." |

### 검증
```bash
# 1) 빈 메시지 전송 → 400 에러
curl -X POST http://localhost:8090/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": ""}'

# 2) 매우 긴 메시지 전송 → 토큰 초과 에러

# 3) 잘못된 파일 업로드 → 에러 응답 확인

# 4) 에러 응답 형식 확인 (code, message, timestamp 필드)
```

---

## 6. Stage 7: 프롬프트 엔지니어링 & 가드레일

### 목표
시스템 프롬프트를 체계적으로 관리하고, RAG 답변 품질을 튜닝하며, 부적절한 입출력을 제어한다.

### 학습 포인트
- 시스템 프롬프트 외부 파일 관리 (하드코딩 탈피)
- RAG 파라미터 튜닝 (청킹 크기, 오버랩, 유사도 임계값)
- 답변 범위 제한 (회사 서비스 관련 질문만 응답)
- 입력 길이 검증, 출력 필터링

### 의존성 추가
없음

### 생성/수정 파일 (4개 생성, 3개 수정)

| 파일 | 변경 내용 |
|------|-----------|
| `resources/prompts/system-default.txt` | **생성** — 기본 시스템 프롬프트 |
| `resources/prompts/system-rag.txt` | **생성** — RAG 전용 시스템 프롬프트 |
| `config/PromptConfig.java` | **생성** — 프롬프트 로딩/관리 설정 |
| `service/InputValidationService.java` | **생성** — 입력 검증 서비스 |
| `config/ChatClientConfig.java` | 외부 프롬프트 파일 참조로 변경 |
| `service/RagService.java` | 프롬프트 파일 참조 + 청킹 파라미터 튜닝 |
| `service/DocumentService.java` | `TokenTextSplitter` 파라미터 커스터마이징 |

### 핵심 코드 패턴

**시스템 프롬프트 외부 관리:**
```
# resources/prompts/system-default.txt
당신은 친절한 한국어 AI 어시스턴트입니다.
모든 답변을 한국어로 제공하세요.

## 답변 규칙
- 확실하지 않은 정보는 "확인이 필요합니다"라고 명시하세요.
- 개인정보나 민감한 정보를 요청받으면 정중히 거절하세요.
- 답변은 간결하고 핵심적으로 작성하세요.
```

**프롬프트 로딩:**
```java
@Configuration
public class PromptConfig {

    @Bean
    public String defaultSystemPrompt() throws IOException {
        return new ClassPathResource("prompts/system-default.txt")
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
```

**ChatClientConfig에서 외부 프롬프트 사용:**
```java
@Bean
public ChatClient chatClient(ChatClient.Builder builder, String defaultSystemPrompt) {
    return builder
            .defaultSystem(defaultSystemPrompt)
            .build();
}
```

**TokenTextSplitter 커스터마이징:**
```java
// 기본값: 800토큰, 오버랩 없음
// 튜닝: 500토큰, 100토큰 오버랩 (문맥 연결성 향상)
TokenTextSplitter splitter = TokenTextSplitter.builder()
        .withChunkSize(500)
        .withMinChunkSizeChars(100)
        .withOverlapSize(100)
        .withKeepSeparator(true)
        .build();
```

**입력 검증:**
```java
@Service
public class InputValidationService {

    private static final int MAX_MESSAGE_LENGTH = 5000;

    public void validate(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("메시지를 입력해주세요.");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("메시지가 너무 깁니다. " + MAX_MESSAGE_LENGTH + "자 이내로 입력해주세요.");
        }
    }
}
```

### RAG 파라미터 튜닝 가이드

| 파라미터 | 현재값 | 튜닝 방향 | 효과 |
|---------|--------|-----------|------|
| `similarityThreshold` | 0.3 | 0.2~0.5 범위 실험 | 낮을수록 더 많은 문서 검색, 높을수록 정확도 향상 |
| `topK` | 5 | 3~10 범위 실험 | 많을수록 컨텍스트 풍부, 적을수록 집중도 향상 |
| `chunkSize` | 800 | 300~1000 범위 실험 | 작을수록 정밀 검색, 클수록 문맥 보존 |
| `overlapSize` | 0 | 50~200 | 오버랩 추가 시 청크 경계의 정보 손실 방지 |

### 검증
```bash
# 1) 프롬프트 변경 후 답변 스타일 변화 확인

# 2) 빈 메시지, 초장문 메시지 → 검증 에러 확인

# 3) RAG 파라미터 변경 전후 답변 품질 비교
#    동일 질문으로 chunkSize, topK 변경 시 답변 차이 관찰

# 4) 가드레일 테스트: 개인정보 요청, 서비스 범위 밖 질문 시 적절히 거절하는지
```

---

## 7. Stage 8: Function Calling (도구 호출)

### 목표
LLM이 외부 시스템 API를 직접 호출하는 패턴을 학습한다.
"내 주문 조회해줘", "오늘 날씨 알려줘" 같은 동적 데이터 요청에 대응할 수 있게 한다.

### 학습 포인트
- Spring AI `@Tool` 어노테이션 기반 도구 정의
- `@ToolParam`으로 파라미터 설명 제공
- `ChatClient`에 도구 등록 (`.tools()`, `.defaultTools()`)
- `ToolContext`를 통한 사용자 컨텍스트 전달
- Function Calling + Memory + Streaming 결합

### 의존성 추가
없음 (Spring AI 내장)

### 생성/수정 파일 (3개 생성, 2개 수정)

| 파일 | 변경 내용 |
|------|-----------|
| `tool/DateTimeTool.java` | **생성** — 날짜/시간 조회 도구 |
| `tool/OrderLookupTool.java` | **생성** — 주문 조회 시뮬레이션 도구 |
| `tool/WeatherTool.java` | **생성** — 날씨 조회 시뮬레이션 도구 |
| `config/ChatClientConfig.java` | 도구 등록 |
| `static/js/app.js` | 도구 호출 결과 표시 |

### 핵심 코드 패턴

**@Tool 어노테이션 기반 도구 정의:**
```java
@Component
public class DateTimeTool {

    @Tool(description = "현재 날짜와 시간을 조회합니다")
    public String getCurrentDateTime() {
        return LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일 HH시 mm분"));
    }
}
```

**주문 조회 시뮬레이션:**
```java
@Component
public class OrderLookupTool {

    @Tool(description = "주문번호로 주문 상태를 조회합니다")
    public String lookupOrder(
            @ToolParam(description = "주문번호 (예: ORD-2024-001)") String orderId) {
        // 실제 서비스에서는 DB 또는 외부 API 호출
        return "주문번호 %s: 배송 중 (예상 도착일: 내일)".formatted(orderId);
    }
}
```

**ChatClient에 도구 등록:**
```java
// 방법 1: 기본 도구로 등록 (모든 호출에 적용)
ChatClient chatClient = builder
        .defaultSystem(systemPrompt)
        .defaultTools(new DateTimeTool(), new OrderLookupTool())
        .build();

// 방법 2: 요청별 도구 등록
chatClient.prompt()
        .user(message)
        .tools(new WeatherTool())
        .call()
        .content();
```

**ToolContext — 사용자 컨텍스트 전달:**
```java
// 도구 내에서 사용자 정보 접근
@Tool(description = "사용자의 주문 내역을 조회합니다")
public String getMyOrders(ToolContext toolContext) {
    String userId = (String) toolContext.getContext().get("userId");
    return orderService.findByUserId(userId);
}

// 호출 시 컨텍스트 전달
chatClient.prompt()
        .user("내 주문 내역 보여줘")
        .tools(new OrderLookupTool())
        .toolContext(Map.of("userId", currentUserId))
        .call()
        .content();
```

### 제약사항
- `@Tool` 메서드는 `Flux`, `Mono`, `CompletableFuture`, `Optional` 등 비동기 타입을 반환할 수 없다
- 도구 이름은 ChatClient 내에서 유일해야 한다
- 도구 호출 중간 메시지는 기본적으로 ChatMemory에 저장되지 않는다

### 검증
```bash
# 1) 날짜/시간 조회
curl -X POST http://localhost:8090/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "지금 몇 시야?"}'
# → LLM이 DateTimeTool을 호출하여 실제 시간 반환

# 2) 주문 조회
curl -X POST http://localhost:8090/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "ORD-2024-001 주문 상태 알려줘"}'
# → OrderLookupTool 호출 후 주문 정보 반환

# 3) 도구 불필요한 질문은 일반 답변으로 처리되는지 확인
curl -X POST http://localhost:8090/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Spring AI가 무엇인가요?"}'
```

---

## 8. Stage 9: 관찰가능성 (Observability)

### 목표
LLM 호출의 토큰 사용량, 응답 시간, 벡터 검색 성능 등을 모니터링하여 운영 비용과 서비스 품질을 추적한다.

### 학습 포인트
- Spring Boot Actuator + Micrometer 연동
- Spring AI 내장 Observation (ChatModel, EmbeddingModel, VectorStore 자동 계측)
- 토큰 사용량 추적 (`gen_ai.client.token.usage`)
- Prometheus 메트릭 포맷 + 간이 대시보드

### 의존성 추가 — `build.gradle`
```gradle
implementation 'org.springframework.boot:spring-boot-starter-actuator'
```

### 설정 추가 — `application.properties`
```properties
# Actuator 엔드포인트 노출
management.endpoints.web.exposure.include=health,metrics,prometheus
management.endpoint.health.show-details=always

# Spring AI Observation 설정 (프롬프트/응답 로깅은 개발 시에만)
spring.ai.chat.observations.log-prompt=false
spring.ai.chat.observations.log-completion=false
```

### 생성/수정 파일 (1개 수정)

| 파일 | 변경 내용 |
|------|-----------|
| `application.properties` | Actuator + Observation 설정 추가 |

> Spring AI 1.1.2는 Actuator가 클래스패스에 있으면 자동으로 LLM/벡터 관련 메트릭을 등록한다.
> 별도 코드 작성 없이 설정만으로 동작한다.

### 자동 수집되는 메트릭

| 메트릭 | 설명 | 활용 |
|--------|------|------|
| `gen_ai_client_token_usage_total` | 토큰 사용량 (입력/출력/합계) | 비용 추적 |
| `gen_ai_client_operation_seconds` | LLM 호출 응답 시간 | 성능 모니터링 |
| `db_vector_client_operation_seconds` | 벡터 검색 응답 시간 | RAG 성능 추적 |
| `spring_ai_chat_client_operation_seconds` | ChatClient 레벨 응답 시간 | E2E 성능 |

### 메트릭 태그 (필터링용)

| 태그 | 예시 | 설명 |
|------|------|------|
| `gen_ai.request.model` | `gemini-3-flash-preview` | 사용 모델 |
| `gen_ai.usage.input_tokens` | `150` | 입력 토큰 수 |
| `gen_ai.usage.output_tokens` | `320` | 출력 토큰 수 |
| `spring.ai.chat.client.stream` | `true/false` | 스트리밍 여부 |

### 검증
```bash
# 1) Actuator 헬스 체크
curl http://localhost:8090/actuator/health

# 2) 채팅 몇 번 수행 후 메트릭 확인
curl http://localhost:8090/actuator/metrics/gen_ai.client.token.usage
# → 토큰 사용량 확인

curl http://localhost:8090/actuator/metrics/gen_ai.client.operation
# → LLM 호출 횟수, 평균 응답 시간

curl http://localhost:8090/actuator/metrics/db.vector.client.operation
# → 벡터 검색 성능

# 3) Prometheus 포맷 (Grafana 연동 시)
curl http://localhost:8090/actuator/prometheus
```

---

## 9. 단계별 파일 변경 요약

### Stage 4 (4개 수정, 1개 의존성 추가)
- **의존성:** `spring-boot-starter-webflux`
- **수정:** `ChatService.java`, `RagService.java`, `ChatController.java`, `RagController.java`, `app.js`

### Stage 5 (1개 생성, 5개 수정, 1개 의존성 추가)
- **의존성:** `spring-ai-starter-model-chat-memory-repository-jdbc`
- **생성:** `ChatMemoryConfig.java`
- **수정:** `ChatClientConfig.java`, `ChatService.java`, `RagService.java`, `ChatController.java`, `RagController.java`, `ChatRequest.java`, `app.js`, `application.properties`

### Stage 6 (4개 생성, 1개 수정)
- **생성:** `GlobalExceptionHandler.java`, `LlmServiceException.java`, `DocumentProcessingException.java`, `ErrorResponse.java`
- **수정:** `app.js`

### Stage 7 (4개 생성, 3개 수정)
- **생성:** `system-default.txt`, `system-rag.txt`, `PromptConfig.java`, `InputValidationService.java`
- **수정:** `ChatClientConfig.java`, `RagService.java`, `DocumentService.java`

### Stage 8 (3개 생성, 2개 수정)
- **생성:** `DateTimeTool.java`, `OrderLookupTool.java`, `WeatherTool.java`
- **수정:** `ChatClientConfig.java`, `app.js`

### Stage 9 (1개 수정, 1개 의존성 추가)
- **의존성:** `spring-boot-starter-actuator`
- **수정:** `application.properties`

---

## 10. 잠재적 이슈 및 대응

| 이슈 | 대응 |
|------|------|
| WebFlux + MVC 공존 시 자동설정 충돌 | `spring.main.web-application-type=servlet` 명시하여 MVC 유지 |
| Streaming 시 에러 핸들링 어려움 | `Flux.onErrorResume()`으로 스트림 내 에러 처리 |
| ChatMemory 무한 증가 | `maxMessages`로 윈도우 크기 제한, 오래된 대화 정리 배치 |
| Function Calling 무한 루프 | 도구 호출 횟수 제한 설정 |
| Observability 메트릭 카디널리티 폭발 | 고카디널리티 태그(conversationId 등) 메트릭에 포함하지 않기 |
| JDBC ChatMemory 테이블 스키마 | `initialize-schema=always` 설정으로 자동 생성, 기존 PgVector DB 재활용 |
