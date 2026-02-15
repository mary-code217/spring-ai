# Spring AI RAG 챗봇 구현 계획서 (Gemini + PgVector)

## 1. 컨텍스트

회사 서비스에 AI 챗봇 도입을 위한 사전 학습/프로토타이핑 프로젝트이다.
Spring Boot 3.5.10 + Spring AI 1.1.2 기반으로 Google Gemini를 LLM/임베딩 모델로 사용하고, 최종적으로 PostgreSQL pgvector를 벡터 저장소로 활용하는 RAG 챗봇을 3단계에 걸쳐 점진적으로 구현한다.

**현재 상태:** Stage 2 완료. 기본 Gemini 챗봇 + SimpleVectorStore 기반 RAG 파이프라인 구현됨.

---

## 2. 아키텍처 개요

```
[Client] --> [ChatController]     --> [ChatService]     --> [Gemini ChatModel]
[Client] --> [DocumentController] --> [DocumentService]  --> [TikaReader → Splitter → VectorStore]
[Client] --> [RagController]      --> [RagService]       --> [QuestionAnswerAdvisor]
                                                                    |
                                                              [VectorStore] ← [EmbeddingModel]
```

**최종 패키지 구조:**
```
com.example.ai/
├── AiApplication.java                     (기존)
├── config/
│   ├── ChatClientConfig.java              (Stage 1)
│   └── VectorStoreConfig.java             (Stage 2 → Stage 3에서 삭제)
├── controller/
│   ├── ChatController.java                (Stage 1)
│   ├── DocumentController.java            (Stage 2)
│   └── RagController.java                 (Stage 2)
├── service/
│   ├── ChatService.java                   (Stage 1)
│   ├── DocumentService.java               (Stage 2)
│   └── RagService.java                    (Stage 2)
└── dto/
    ├── ChatRequest.java                   (Stage 1)
    ├── ChatResponse.java                  (Stage 1)
    └── DocumentResponse.java              (Stage 2)
```

---

## 3. Stage 1: 기본 Gemini 챗봇

### 목표
Gemini API를 통한 단순 질의/응답 REST API (`POST /api/chat`)

### 의존성
변경 없음 (이미 `spring-ai-starter-model-google-genai` 포함)

### 설정 추가 — `application.properties`
```properties
server.port=8090
spring.ai.google.genai.api-key=${GOOGLE_GENAI_API_KEY}
spring.ai.google.genai.chat.options.model=gemini-3-flash-preview
spring.ai.google.genai.chat.options.temperature=0.7
```
> API 키는 환경변수로 주입. 하드코딩 금지. 포트는 8090 사용 (8080 충돌 방지).

### 로컬 개발 설정
`application-local.properties`에 API 키를 직접 설정하고 `.gitignore`에 추가하여 커밋 방지.
`spring.config.import=optional:classpath:application-local.properties`로 자동 임포트되므로 프로필 지정 불필요.

### 생성 파일 (6개)

| 파일 | 역할 |
|------|------|
| `dto/ChatRequest.java` | 요청 DTO — `message` 필드 |
| `dto/ChatResponse.java` | 응답 DTO — `answer` 필드 |
| `config/ChatClientConfig.java` | `ChatClient` 빈 등록 + 기본 시스템 프롬프트 설정 |
| `service/ChatService.java` | `chatClient.prompt().user(msg).call().content()` 패턴 |
| `controller/ChatController.java` | `POST /api/chat` 엔드포인트 |
| `static/index.html` | 대화형 챗봇 웹 UI (HTML 구조) |
| `static/css/style.css` | UI 스타일시트 (shadcn 스타일) |
| `static/js/app.js` | 프론트엔드 로직 (모드 전환, 파일 업로드, 채팅) |

### 핵심 코드 패턴
- `ChatClient.Builder`는 Spring AI starter가 자동 주입
- `ChatClientConfig`에서 시스템 프롬프트를 설정하여 한국어 응답 유도
- `ChatService`는 fluent API: `chatClient.prompt().user(message).call().content()`

### 검증
- 브라우저: `http://localhost:8090` 접속하여 대화형 UI로 테스트
- curl:
```bash
curl -X POST http://localhost:8090/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Spring AI가 무엇인가요?"}'
```

---

## 4. Stage 2: RAG 구현 (SimpleVectorStore)

### 목표
문서 업로드 → 청킹 → 임베딩 → 인메모리 벡터 저장 → 질의 시 유사 문서 검색 후 컨텍스트 주입

### 의존성 추가 — `build.gradle`
```gradle
implementation 'org.springframework.ai:spring-ai-starter-model-google-genai-embedding'
implementation 'org.springframework.ai:spring-ai-tika-document-reader'
implementation 'org.springframework.ai:spring-ai-advisors-vector-store'
```

### 설정 추가 — `application.properties`
```properties
# 임베딩 모델
spring.ai.google.genai.embedding.api-key=${GOOGLE_GENAI_API_KEY}
spring.ai.google.genai.embedding.text.options.model=gemini-embedding-001
spring.ai.google.genai.embedding.text.options.dimensions=3072

# 파일 업로드 제한
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB
```

### 폴더 생성
- `src/main/resources/docs/` — RAG 대상 문서 보관용

### 생성 파일 (6개)

| 파일 | 역할 |
|------|------|
| `config/VectorStoreConfig.java` | `SimpleVectorStore` 빈 등록 (인메모리) |
| `dto/DocumentResponse.java` | 문서 처리 결과 DTO — `chunksProcessed`, `fileName`, `message` |
| `service/DocumentService.java` | `TikaDocumentReader` → `TokenTextSplitter` → `vectorStore.add()` |
| `controller/DocumentController.java` | `POST /api/documents` — MultipartFile 업로드 |
| `service/RagService.java` | `QuestionAnswerAdvisor` + `VectorStore` 기반 RAG 파이프라인 |
| `controller/RagController.java` | `POST /api/rag/chat` 엔드포인트 |

### 핵심 코드 패턴

**DocumentService — ETL 파이프라인:**
```
TikaDocumentReader(resource).get()        // 문서 읽기
→ TokenTextSplitter().apply(documents)     // 청킹 (기본 800토큰)
→ vectorStore.add(chunks)                  // 임베딩 + 저장
```

**RagService — QuestionAnswerAdvisor:**
```java
chatClientBuilder
    .defaultAdvisors(
        QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(SearchRequest.builder()
                .similarityThreshold(0.3)
                .topK(5)
                .build())
            .build())
    .build()
    .prompt().user(message).call().content();
```
> `similarityThreshold`는 0.3으로 설정 (0.7에서 낮춤 — Gemini 임베딩 특성상 유사도 점수가 낮게 나오는 경향이 있어 조정)
- `ChatClient.Builder`를 주입받아 RAG 전용 ChatClient를 생성 (Stage 1의 일반 chatClient와 분리)
- `QuestionAnswerAdvisor`가 벡터 검색 → 컨텍스트 주입 → LLM 호출을 자동 처리

### 검증
```bash
# 1) 문서 업로드
curl -X POST http://localhost:8090/api/documents -F "file=@test.pdf"

# 2) RAG 채팅
curl -X POST http://localhost:8090/api/rag/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "업로드한 문서의 주요 내용은?"}'

# 3) 비교: 같은 질문을 /api/chat으로 보내서 RAG 유무 차이 확인
```

---

## 5. Stage 3: PgVector 통합

### 목표
인메모리 SimpleVectorStore → PostgreSQL pgvector로 교체. 영속적 벡터 저장.

### 의존성 추가 — `build.gradle`
```gradle
implementation 'org.springframework.ai:spring-ai-starter-vector-store-pgvector'
implementation 'org.postgresql:postgresql'
```

### Docker Compose — `docker-compose.yml` (프로젝트 루트)
```yaml
version: '3.8'
services:
  postgres:
    image: pgvector/pgvector:pg16
    container_name: spring-ai-pgvector
    environment:
      POSTGRES_DB: spring_ai
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - pgvector_data:/var/lib/postgresql/data

volumes:
  pgvector_data:
```

### 설정 추가 — `application.properties`
```properties
# PostgreSQL
spring.datasource.url=jdbc:postgresql://localhost:5432/spring_ai
spring.datasource.username=postgres
spring.datasource.password=postgres
spring.datasource.driver-class-name=org.postgresql.Driver

# PgVector
spring.ai.vectorstore.pgvector.initialize-schema=true
spring.ai.vectorstore.pgvector.index-type=HNSW
spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE
spring.ai.vectorstore.pgvector.dimensions=3072
```

### 핵심 변경사항

| 변경 | 내용 |
|------|------|
| `VectorStoreConfig.java` | **삭제** — `spring-ai-starter-vector-store-pgvector`가 `PgVectorStore` 빈을 자동 구성 |
| 서비스/컨트롤러 코드 | **변경 없음** — `VectorStore` 인터페이스에 의존하므로 구현체 교체 시 코드 수정 불필요 |
| 문서 데이터 | **재인제스트 필요** — 인메모리 데이터는 PgVector로 자동 마이그레이션되지 않음 |

> Spring AI 추상화의 핵심 이점: `VectorStore` 인터페이스 덕분에 저장소 교체 시 비즈니스 로직 수정이 없다.

### 검증
```bash
# 1) Docker 시작
docker compose up -d

# 2) pgvector 확장 확인
docker exec -it spring-ai-pgvector psql -U postgres -d spring_ai -c "\dx"

# 3) 앱 실행 후 문서 업로드 & RAG 채팅 테스트 (Stage 2와 동일)

# 4) 영속성 검증: 앱 재시작 후 재인제스트 없이 RAG 채팅 정상 동작 확인

# 5) 저장 데이터 확인
docker exec -it spring-ai-pgvector psql -U postgres -d spring_ai -c "SELECT COUNT(*) FROM vector_store;"
```

---

## 6. 잠재적 이슈 및 대응

| 이슈 | 대응 |
|------|------|
| Chat/Embedding API 키를 별도로 설정해야 함 | `spring.ai.google.genai.embedding.api-key`를 반드시 추가 |
| SimpleVectorStore ↔ PgVectorStore 빈 충돌 | Stage 3에서 VectorStoreConfig 삭제 (또는 `@Profile("simple")` 분리) |
| 임베딩 차원 불일치 | PgVector dimensions(3072)과 gemini-embedding-001 출력 차원(3072) 일치 확인 |
| Tika 전이 의존성 충돌 | 발생 시 `build.gradle`에서 exclude로 해결 |
| Docker 미설치 | Stage 3 전에 Docker Desktop 설치 필요 |

---

## 7. 단계별 파일 변경 요약

### Stage 1 ✅ (8개 생성, 1개 수정)
- **수정:** `application.properties`
- **생성:** `ChatRequest.java`, `ChatResponse.java`, `ChatClientConfig.java`, `ChatService.java`, `ChatController.java`, `static/index.html`, `static/css/style.css`, `static/js/app.js`

### Stage 2 ✅ (6개 생성, 1개 폴더 생성, 2개 수정)
- **수정:** `build.gradle`, `application.properties`
- **폴더:** `src/main/resources/docs/`
- **생성:** `VectorStoreConfig.java`, `DocumentResponse.java`, `DocumentService.java`, `DocumentController.java`, `RagService.java`, `RagController.java`

### Stage 3 (1개 생성, 2개 수정, 1개 삭제)
- **생성:** `docker-compose.yml`
- **수정:** `build.gradle`, `application.properties`
- **삭제:** `VectorStoreConfig.java`
