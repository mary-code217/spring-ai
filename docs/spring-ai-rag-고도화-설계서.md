# Spring AI RAG 고도화 구현 계획서 (Stage 10~13)

## 1. 컨텍스트

Stage 4~9(프로덕션 품질 기능) 완료를 전제로, RAG 파이프라인의 **검색 품질 자체를 끌어올리는** 고도화 단계를 설계한다.
현재 RAG 파이프라인은 **Naive RAG** 방식으로, 검색 품질에 근본적인 한계가 있다.

### Naive RAG의 문제점

| 문제 | 설명 | 예시 |
|------|------|------|
| 컨텍스트 손실 | 청킹 시 원본 문서의 맥락(출처, 섹션 등)이 사라짐 | "15% 증가했다" → 어느 회사? 몇 분기? |
| 단일 검색 | 한 번의 벡터 유사도 검색으로 끝남 | 질문 의도와 다른 청크가 상위에 올 수 있음 |
| 키워드 무시 | 시맨틱 유사도만 사용, 정확한 키워드 매칭 부재 | "ORD-2024-001" 같은 고유명사 검색 취약 |
| 재검증 없음 | 검색 결과가 실제로 유용한지 판단하지 않음 | 관련 없는 청크가 LLM에 그대로 전달 |

이 문서는 Naive RAG → Advanced RAG → Agentic RAG로 **검색 품질 자체를 끌어올리는** 4개 학습 단계를 설계한다.

### 선행 조건 및 적용 시점

이 문서의 Stage는 **Stage 9 완료 이후** 적용하는 것을 전제로 한다.

| 선행 Stage | 이 문서에서 필요한 이유 |
|-----------|----------------------|
| **Stage 5 (Chat Memory)** | Stage 11의 `CompressionQueryTransformer`가 대화 히스토리를 참조 |
| **Stage 7 (프롬프트 엔지니어링)** | Stage 10의 청킹 파라미터 튜닝이 Stage 7 결과 위에 적용 |
| **Stage 8 (Function Calling)** | Stage 13의 `@Tool` 기반 Agentic RAG가 도구 호출 패턴을 전제 |

> **조기 적용이 필요한 경우:** Stage 10(ETL 고도화)과 Stage 11(검색 고도화, CompressionQueryTransformer 제외)은
> Stage 3 완료 시점에서도 독립 적용이 가능하다. 단, 아래 사항을 확인해야 한다:
> - `build.gradle`에 `spring-ai-rag` 의존성 추가
> - `ChatRequest` DTO에 `conversationId` 필드가 없으면 관련 코드 제거
> - Stage 13은 반드시 Stage 8(Function Calling) 이후에 진행

---

## 2. 단계 의존 관계

```
Stage 10 (ETL 고도화) ──────────────┐
                                     ├──→ Stage 12 (Reranker)
Stage 11 (검색 고도화) ──────────────┘          │
                                                ▼
                                     Stage 13 (Agentic RAG)
```

> **추천 순서:** Stage 10 → 11 → 12 → 13
> Stage 10과 11은 독립 수행 가능하나, Stage 10을 먼저 하면 Stage 11의 메타데이터 필터링을 바로 활용할 수 있다.
> Stage 12는 Stage 11의 검색 파이프라인 위에서 동작한다.
> Stage 13은 Stage 8(Function Calling) + Stage 12(Reranker)를 전제로 한다.

### 전체 로드맵에서의 위치

```
[Stage 1~3]   기본 RAG 챗봇 구축
[Stage 4~9]   프로덕션 품질 기능 (Streaming, Memory, 에러, Tool, 모니터링)
[Stage 10~13] RAG 검색 품질 고도화  ← 이 문서
```

---

## 3. 공통 의존성 추가

Stage 10~13 전체에서 공통으로 필요한 의존성이다. 한 번만 추가하면 된다.

### build.gradle

```gradle
// RAG 고도화 모듈 (RetrievalAugmentationAdvisor, QueryTransformer 등)
implementation 'org.springframework.ai:spring-ai-rag'
```

> 기존 `spring-ai-advisors-vector-store` (QuestionAnswerAdvisor)와 별개의 모듈이다.
> `spring-ai-rag`는 `RetrievalAugmentationAdvisor`, `QueryTransformer`, `DocumentPostProcessor` 등 고도화 RAG API를 포함한다.

---

## 4. Stage 10: ETL 고도화 (검색 재료 품질 개선)

### 목표

문서 인제스트 파이프라인을 개선하여 **청크의 품질 자체를 높인다**. 청킹 전략을 개선하고, 각 청크에 키워드/요약/출처 메타데이터를 자동으로 부여한다.

### 왜 필요한가

현재 ETL 파이프라인:
```
PDF → Tika 텍스트 추출 → 800토큰 단위로 자름 → 벡터화 → 저장
```

문제:
```
원본: [A사 2024년 3분기 실적 보고서] 마케팅 비용이 전 분기 대비 15% 증가했다.

현재 청크: "마케팅 비용이 전 분기 대비 15% 증가했다. 반면..."
→ 메타데이터: { source: "upload.pdf" }  ← 이게 전부

개선 후 청크: "마케팅 비용이 전 분기 대비 15% 증가했다. 반면..."
→ 메타데이터: {
    source: "A사_3분기_보고서.pdf",
    excerpt_keywords: ["마케팅", "비용", "증가", "전분기", "15%"],
    section_summary: "A사 3분기 마케팅 비용 분석 섹션",
    prev_section_summary: "A사 3분기 매출 현황",
    next_section_summary: "A사 3분기 R&D 투자 현황"
  }
```

### 학습 포인트
- `TokenTextSplitter` 파라미터 최적화 (청크 크기, 오버랩)
- `KeywordMetadataEnricher`로 청크별 키워드 자동 추출
- `SummaryMetadataEnricher`로 청크별 요약 + 전후 문맥 요약 생성
- `Document` 객체의 메타데이터 커스터마이징
- ETL 파이프라인 체이닝 (Reader → Splitter → Enricher → Store)

### 생성/수정 파일 (3개 생성, 2개 수정)

| 파일 | 변경 내용 |
|------|-----------|
| `config/EtlPipelineConfig.java` | **생성** — TokenTextSplitter, Enricher 빈 설정 |
| `config/AsyncConfig.java` | **생성** — `@EnableAsync` + 비동기 인제스트용 스레드풀 설정 |
| `service/DocumentEnrichmentService.java` | **생성** — 메타데이터 강화 파이프라인 |
| `service/DocumentService.java` | ETL 파이프라인 개선 (Enricher + 비동기 처리) |
| `application.properties` | Enricher 관련 설정 추가 |

### 핵심 코드 패턴

**EtlPipelineConfig — 청킹/강화 빈 설정:**

```java
@Configuration
public class EtlPipelineConfig {

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return TokenTextSplitter.builder()
                .withChunkSize(500)           // 800 → 500 (더 정밀한 검색)
                .withMinChunkSizeChars(100)
                .withOverlapSize(100)          // 0 → 100 (경계 정보 손실 방지)
                .withKeepSeparator(true)
                .build();
    }

    @Bean
    public KeywordMetadataEnricher keywordMetadataEnricher(ChatModel chatModel) {
        return KeywordMetadataEnricher.builder(chatModel)
                .keywordCount(5)
                .build();
    }

    @Bean
    public SummaryMetadataEnricher summaryMetadataEnricher(ChatModel chatModel) {
        return new SummaryMetadataEnricher(
                chatModel,
                List.of(
                        SummaryMetadataEnricher.SummaryType.PREVIOUS,
                        SummaryMetadataEnricher.SummaryType.CURRENT,
                        SummaryMetadataEnricher.SummaryType.NEXT
                )
        );
    }
}
```

> **SummaryType 설명:**
> - `CURRENT`: 현재 청크의 요약 → `section_summary` 메타데이터에 저장
> - `PREVIOUS`: 이전 청크의 요약 → `prev_section_summary`에 저장
> - `NEXT`: 다음 청크의 요약 → `next_section_summary`에 저장
>
> 이를 통해 각 청크가 **자기 주변 맥락을 알고 있게** 된다.

**DocumentEnrichmentService — 강화 파이프라인:**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentEnrichmentService {

    private final KeywordMetadataEnricher keywordEnricher;
    private final SummaryMetadataEnricher summaryEnricher;

    public List<Document> enrich(List<Document> documents) {
        log.info("키워드 추출 시작 - {} 청크", documents.size());
        List<Document> enriched = keywordEnricher.apply(documents);

        // KeywordEnricher가 키워드를 콤마 구분 문자열로 저장하므로
        // JSON 배열(List)로 변환하여 FilterExpression의 in 연산자 호환성 확보
        enriched.forEach(doc -> {
            Object keywords = doc.getMetadata().get("excerpt_keywords");
            if (keywords instanceof String keywordStr) {
                List<String> keywordList = Arrays.stream(keywordStr.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                doc.getMetadata().put("excerpt_keywords", keywordList);
            }
        });

        log.info("요약 생성 시작 - {} 청크", enriched.size());
        enriched = summaryEnricher.apply(enriched);

        log.info("메타데이터 강화 완료");
        return enriched;
    }
}
```

> **주의 1:** `KeywordMetadataEnricher`는 키워드를 `"마케팅, 비용, 증가"` 형태의 **콤마 구분 문자열**로 저장한다.
> PgVectorStore의 `in` 필터 연산자는 **JSON 배열**을 기대하므로, 반드시 `List<String>`으로 변환해야 한다.
> 변환하지 않으면 `excerpt_keywords in ['마케팅']` 필터가 동작하지 않는다.
>
> **주의 2:** Enricher는 각 청크마다 LLM API를 호출한다. 100개 청크 × 2개 Enricher = 200회 API 호출.
> 대량 문서 처리 시 비용과 시간을 고려해야 한다.

**DocumentService — 개선된 ETL 파이프라인:**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;
    private final DocumentEnrichmentService enrichmentService;

    public DocumentResponse processDocument(MultipartFile file) throws IOException {
        // 1단계: 문서 읽기 (Extract)
        Resource resource = file.getResource();
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> documents = reader.get();

        // 1-1: 원본 파일명을 메타데이터에 추가
        String fileName = file.getOriginalFilename();
        documents.forEach(doc ->
                doc.getMetadata().put("source_file", fileName));

        // 2단계: 청킹 (Transform) — 500토큰, 100토큰 오버랩
        List<Document> chunks = textSplitter.apply(documents);
        // → 메타데이터(source_file 등)는 모든 청크에 자동 복사됨

        // 3단계: 메타데이터 강화 (Enrich) — 키워드 추출 + 요약 생성
        List<Document> enrichedChunks = enrichmentService.enrich(chunks);

        // 4단계: 벡터화 + 저장 (Load)
        vectorStore.add(enrichedChunks);

        log.info("문서 처리 완료: {} → {} 청크 (메타데이터 강화 적용)", fileName, enrichedChunks.size());
        return new DocumentResponse(fileName, enrichedChunks.size(), "문서 처리가 완료되었습니다.");
    }
}
```

### 개선 전후 비교

```
[개선 전] ETL 파이프라인
  Tika → TokenTextSplitter(800, 오버랩 0) → vectorStore.add()

  저장되는 청크:
  {
    content: "마케팅 비용이 전 분기 대비 15% 증가했다...",
    metadata: { source: "upload.pdf" }
  }

[개선 후] ETL 파이프라인
  Tika → 메타데이터 태깅 → TokenTextSplitter(500, 오버랩 100)
       → KeywordEnricher → SummaryEnricher → vectorStore.add()

  저장되는 청크:
  {
    content: "마케팅 비용이 전 분기 대비 15% 증가했다...",
    metadata: {
      source_file: "A사_3분기_보고서.pdf",
      excerpt_keywords: ["마케팅", "비용", "증가", "전분기대비", "15%"],
      section_summary: "A사 3분기 마케팅 비용이 전분기 대비 15% 증가한 원인 분석",
      prev_section_summary: "A사 3분기 전체 매출 현황 요약",
      next_section_summary: "A사 3분기 R&D 투자 현황 및 계획"
    }
  }
```

### 검증

```bash
# 1) 기존 벡터 스토어 초기화 (메타데이터 구조 변경으로 재인제스트 필요)
docker exec -it spring-ai-pgvector psql -U postgres -d spring_ai \
  -c "DELETE FROM vector_store;"

# 2) 문서 업로드 (강화 ETL 적용)
curl -X POST http://localhost:8090/api/documents -F "file=@test.pdf"
# → 응답에서 chunksProcessed 확인
# → 로그에서 "키워드 추출 시작", "요약 생성 시작" 메시지 확인

# 3) 저장된 메타데이터 확인
docker exec -it spring-ai-pgvector psql -U postgres -d spring_ai \
  -c "SELECT metadata FROM vector_store LIMIT 3;"
# → excerpt_keywords, section_summary 등이 포함되어 있어야 정상

# 4) RAG 채팅으로 품질 차이 비교
curl -X POST http://localhost:8090/api/rag/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "문서의 주요 키워드가 뭐야?"}'
```

### 비동기 인제스트 (대용량 문서 대응)

Enricher가 청크당 약 4회 LLM API를 호출하므로, 동기 업로드 경로에서 처리하면 대용량 문서에서 타임아웃이 발생한다.
**비동기 인제스트 + 상태 조회 API**를 기본 패턴으로 사용한다.

**AsyncConfig — 비동기 활성화 + 스레드풀 설정:**

```java
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "etlTaskExecutor")
    public TaskExecutor etlTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);        // 동시 인제스트 최소 스레드
        executor.setMaxPoolSize(4);         // 동시 인제스트 최대 스레드
        executor.setQueueCapacity(10);      // 대기열 크기
        executor.setThreadNamePrefix("etl-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
```

> **`@EnableAsync` 없으면 `@Async`가 무시되고 동기 실행된다.** 반드시 별도 `@Configuration` 클래스에 선언.
> `CallerRunsPolicy`: 대기열이 가득 차면 호출 스레드(HTTP 스레드)에서 직접 실행하여 요청 유실 방지.
> LLM API Rate Limit을 고려하여 `maxPoolSize`를 4 이하로 제한한다.

**DocumentService — 비동기 처리:**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;
    private final DocumentEnrichmentService enrichmentService;

    // 인제스트 상태 관리 (프로덕션에서는 DB/Redis 사용)
    private final ConcurrentHashMap<String, IngestStatus> statusMap = new ConcurrentHashMap<>();

    /**
     * 비동기 문서 처리 — 즉시 jobId를 반환하고 백그라운드에서 ETL 수행.
     * 클라이언트는 GET /api/documents/{jobId}/status 로 진행 상태를 폴링한다.
     */
    @Async("etlTaskExecutor")
    public void processDocumentAsync(String jobId, MultipartFile file) {
        String fileName = file.getOriginalFilename();
        statusMap.put(jobId, new IngestStatus("PROCESSING", 0, fileName));

        try {
            Resource resource = file.getResource();
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<Document> documents = reader.get();
            documents.forEach(doc -> doc.getMetadata().put("source_file", fileName));

            List<Document> chunks = textSplitter.apply(documents);
            statusMap.put(jobId, new IngestStatus("ENRICHING", chunks.size(), fileName));

            List<Document> enrichedChunks = enrichmentService.enrich(chunks);

            vectorStore.add(enrichedChunks);
            statusMap.put(jobId, new IngestStatus("COMPLETED", enrichedChunks.size(), fileName));
            log.info("비동기 문서 처리 완료: {} → {} 청크", fileName, enrichedChunks.size());

        } catch (Exception e) {
            log.error("문서 처리 실패: {}", fileName, e);
            statusMap.put(jobId, new IngestStatus("FAILED", 0, fileName));
        }
    }

    public IngestStatus getStatus(String jobId) {
        return statusMap.getOrDefault(jobId, new IngestStatus("NOT_FOUND", 0, null));
    }

    public record IngestStatus(String status, int chunksProcessed, String fileName) {}
}
```

**Controller — 업로드 + 상태 조회:**

```java
@PostMapping("/api/documents")
public ResponseEntity<Map<String, String>> uploadDocument(@RequestParam MultipartFile file) {
    String jobId = UUID.randomUUID().toString();
    documentService.processDocumentAsync(jobId, file);
    return ResponseEntity.accepted()
            .body(Map.of("jobId", jobId, "message", "문서 처리가 시작되었습니다."));
}

@GetMapping("/api/documents/{jobId}/status")
public ResponseEntity<DocumentService.IngestStatus> getStatus(@PathVariable String jobId) {
    return ResponseEntity.ok(documentService.getStatus(jobId));
}
```

> **상태 흐름:** `PROCESSING` → `ENRICHING` → `COMPLETED` (또는 `FAILED`)
> 프론트엔드에서 `/api/documents/{jobId}/status`를 폴링하여 진행 상태를 표시한다.

### 비용 주의사항

| 항목 | 예상 API 호출 | 설명 |
|------|-------------|------|
| KeywordEnricher | 청크당 1회 | 5개 키워드 추출 |
| SummaryEnricher | 청크당 3회 (PREV/CURR/NEXT) | 전후 문맥 요약 |
| **합계** | **청크당 약 4회 LLM 호출** | 100청크 = 400회 호출 |

> 대량 문서 처리 시에는 Enricher 적용을 선택적으로 할 수 있다.
> 예: 중요 문서만 SummaryEnricher 적용, 일반 문서는 KeywordEnricher만 적용.

---

## 5. Stage 11: 검색 고도화 (똑똑한 검색)

### 목표

단순 벡터 유사도 검색을 넘어, **쿼리 변환 + 멀티 쿼리 + 메타데이터 필터링**으로 검색 품질을 높인다.
기존 `QuestionAnswerAdvisor`를 `RetrievalAugmentationAdvisor`로 교체한다.

### 왜 필요한가

현재 검색 방식:
```
"A사 3분기 마케팅 비용 증가 원인이 뭐야?"
    → 그대로 임베딩 → 벡터 유사도 검색 → top-5 반환
```

문제:
- 질문 형태("~뭐야?")가 그대로 임베딩되어 검색 품질 저하
- 하나의 쿼리로만 검색하니 다양한 관점의 청크를 놓침
- 모든 문서에서 검색하니 관련 없는 문서의 청크가 섞임

개선 후:
```
"A사 3분기 마케팅 비용 증가 원인이 뭐야?"
    │
    ├─ RewriteQueryTransformer
    │   → "A사 2024년 3분기 마케팅 비용 증가 원인 분석"
    │
    ├─ MultiQueryExpander (3개로 확장)
    │   → "A사 3분기 마케팅 비용 변화"
    │   → "A사 3분기 마케팅 예산 집행 현황"
    │   → "A사 3분기 비용 증가 요인 분석"
    │
    ├─ 메타데이터 필터링
    │   → source_file에 "A사" 포함된 문서만 검색
    │
    └─ 3개 쿼리 × 필터링된 문서 → 중복 제거 → 최종 결과
```

### 학습 포인트
- `RetrievalAugmentationAdvisor` (QuestionAnswerAdvisor의 상위 호환)
- `VectorStoreDocumentRetriever` 설정
- `RewriteQueryTransformer`로 자연어 질문 → 검색 최적화 쿼리 변환
- `CompressionQueryTransformer`로 대화 히스토리 기반 질문 압축
- `MultiQueryExpander`로 다관점 멀티 쿼리 생성
- `FilterExpression`으로 메타데이터 기반 문서 필터링
- QueryTransformer 체이닝 (여러 변환기 순차 적용)

### 생성/수정 파일 (2개 생성, 2개 수정)

| 파일 | 변경 내용 |
|------|-----------|
| `config/RagAdvisorConfig.java` | **생성** — RetrievalAugmentationAdvisor 빈 설정 |
| `dto/RagChatRequest.java` | **생성** — 필터 조건 포함 RAG 요청 DTO |
| `service/RagService.java` | QuestionAnswerAdvisor → RetrievalAugmentationAdvisor 교체 |
| `controller/RagController.java` | 필터 파라미터 수신 엔드포인트 추가 |

### 핵심 코드 패턴

**RagAdvisorConfig — 고도화 RAG Advisor 설정:**

```java
@Configuration
@RequiredArgsConstructor
public class RagAdvisorConfig {

    private final VectorStore vectorStore;

    @Bean
    public Advisor retrievalAugmentationAdvisor(ChatClient.Builder chatClientBuilder) {
        // 1) 쿼리 변환: 자연어 → 검색 최적화 쿼리
        QueryTransformer rewriteTransformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .build();

        // 2) 멀티 쿼리: 하나의 질문 → 3개 관점으로 확장
        QueryExpander multiQueryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(chatClientBuilder)
                .numberOfQueries(3)
                .includeOriginal(true)
                .build();

        // 3) 문서 검색기: PgVector + 메타데이터 필터링
        DocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(0.3)
                .topK(10)   // Reranker 적용 전이므로 넓게 검색
                .build();

        // 4) 전체 파이프라인 조립
        return RetrievalAugmentationAdvisor.builder()
                .queryTransformers(rewriteTransformer)
                .queryExpander(multiQueryExpander)
                .documentRetriever(documentRetriever)
                .build();
    }
}
```

> **QuestionAnswerAdvisor vs RetrievalAugmentationAdvisor 비교:**
>
> | 기능 | QuestionAnswerAdvisor | RetrievalAugmentationAdvisor |
> |------|----------------------|------------------------------|
> | 기본 벡터 검색 | O | O |
> | 쿼리 변환 | X | O (QueryTransformer) |
> | 멀티 쿼리 | X | O (QueryExpander) |
> | 검색 후처리 | X | O (DocumentPostProcessor) |
> | 문서 조인 전략 | 고정 | 커스터마이징 가능 |

**RagChatRequest — 필터 포함 요청 DTO:**

```java
public record RagChatRequest(
        String message,
        String conversationId,
        String sourceFile,       // 특정 파일에서만 검색 (선택)
        List<String> keywords    // 특정 키워드 포함 문서만 검색 (선택)
) {}
```

**RagService — 고도화 RAG 서비스:**

```java
@Service
@Slf4j
public class RagService {

    private final ChatClient ragChatClient;
    private final Advisor retrievalAugmentationAdvisor;

    public RagService(
            ChatClient.Builder chatClientBuilder,
            @Qualifier("retrievalAugmentationAdvisor") Advisor ragAdvisor) {

        this.retrievalAugmentationAdvisor = ragAdvisor;

        this.ragChatClient = chatClientBuilder
                .defaultSystem("""
                    당신은 제공된 문서 컨텍스트를 기반으로 정확하게 답변하는 한국어 AI 어시스턴트입니다.
                    컨텍스트에 관련 정보가 없으면 '제공된 문서에서 해당 정보를 찾을 수 없습니다.'라고 답변하세요.
                    답변 시 어느 문서(출처)에서 정보를 가져왔는지 명시하세요.
                    """)
                .defaultAdvisors(retrievalAugmentationAdvisor)
                .build();
    }

    // 기본 RAG 채팅 (필터 없음)
    public String chat(String message, String conversationId) {
        return ragChatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }

    // 필터링 RAG 채팅 (특정 파일/키워드로 범위 제한)
    public String chatWithFilter(RagChatRequest request) {
        var promptBuilder = ragChatClient.prompt()
                .user(request.message());

        // 동적 메타데이터 필터링 — FilterExpressionBuilder 사용 (인젝션 방지)
        FilterExpression filterExpression = buildFilterExpression(request);
        if (filterExpression != null) {
            promptBuilder.advisors(a -> a.param(
                    VectorStoreDocumentRetriever.FILTER_EXPRESSION, filterExpression));
        }

        if (request.conversationId() != null) {
            promptBuilder.advisors(a -> a.param(
                    ChatMemory.CONVERSATION_ID, request.conversationId()));
        }

        return promptBuilder.call().content();
    }

    /**
     * 사용자 입력을 FilterExpressionBuilder로 안전하게 변환한다.
     * 문자열 템플릿(String.format) 사용 금지 — 필터 식 인젝션 방지.
     *
     * 주의: FilterExpressionBuilder 내부 API(Op 클래스 등)는 Spring AI 버전에 따라
     * 변경될 수 있으므로, 공개 API인 eq/in/and만 사용한다.
     * 조건이 3개 이상 필요한 경우 구현 시 Spring AI 버전별 동작을 검증할 것.
     */
    private FilterExpression buildFilterExpression(RagChatRequest request) {
        // 1) 업로드된 파일 목록에서 화이트리스트 검증
        String validatedSourceFile = null;
        if (request.sourceFile() != null && !request.sourceFile().isBlank()) {
            validatedSourceFile = validateSourceFile(request.sourceFile());
        }

        List<String> validatedKeywords = List.of();
        if (request.keywords() != null && !request.keywords().isEmpty()) {
            validatedKeywords = request.keywords().stream()
                    .map(String::trim)
                    .filter(k -> !k.isEmpty() && k.length() <= 50)  // 길이 제한
                    .toList();
        }

        // 2) FilterExpressionBuilder 공개 API만 사용하여 필터 생성
        FilterExpressionBuilder b = new FilterExpressionBuilder();

        boolean hasSourceFile = validatedSourceFile != null;
        boolean hasKeywords = !validatedKeywords.isEmpty();

        if (hasSourceFile && hasKeywords) {
            // 두 조건 AND 결합 — 공개 API b.and(Op, Op) 사용
            return b.and(
                    b.eq("source_file", validatedSourceFile),
                    b.in("excerpt_keywords", validatedKeywords.toArray(new String[0]))
            ).build();
        } else if (hasSourceFile) {
            return b.eq("source_file", validatedSourceFile).build();
        } else if (hasKeywords) {
            return b.in("excerpt_keywords", validatedKeywords.toArray(new String[0])).build();
        }

        return null;
    }

    /**
     * 실제 벡터 스토어에 저장된 source_file 목록과 대조하여 검증한다.
     * 존재하지 않는 파일명이면 null 반환 (검색 결과 0건 방지).
     */
    private String validateSourceFile(String requestedFile) {
        // 벡터 스토어에서 고유 source_file 목록을 조회하여 화이트리스트 검증
        // 실제 구현 시 캐싱 권장 (파일 목록은 자주 변하지 않음)
        List<String> knownFiles = getKnownSourceFiles();
        if (knownFiles.contains(requestedFile)) {
            return requestedFile;
        }
        log.warn("알 수 없는 source_file 요청: {}", requestedFile);
        return null;
    }

    /**
     * ⚠️ 반드시 구현 필요 (미구현 시 배포 금지)
     * 현재 List.of()를 반환하므로, 이 상태로는 모든 source_file 검증이 실패하여
     * 필터 조건이 항상 무시된다. 즉, 필터링 없이 전체 검색이 수행되어
     * 화이트리스트 검증의 의미가 없어진다.
     */
    private List<String> getKnownSourceFiles() {
        // 구현 예시:
        // return jdbcTemplate.queryForList(
        //     "SELECT DISTINCT metadata->>'source_file' FROM vector_store WHERE metadata->>'source_file' IS NOT NULL",
        //     String.class);
        //
        // 성능 최적화: @Cacheable(value = "knownSourceFiles", cacheManager = "caffeineCacheManager")
        // 또는 ConcurrentHashMap + TTL 5분으로 수동 캐싱
        throw new UnsupportedOperationException(
                "getKnownSourceFiles() 미구현 — JdbcTemplate으로 벡터 스토어의 고유 source_file 목록을 조회하도록 구현 필요");
    }
}
```

> **⚠️ 구현 필수 체크리스트:**
> 1. `JdbcTemplate` 빈을 `RagService`에 주입
> 2. 위 SQL로 벡터 스토어의 고유 `source_file` 목록 조회
> 3. 결과를 캐싱 (파일 업로드 시 캐시 무효화)
> 4. `MetadataSearchTool.getKnownSourceFiles()`도 동일하게 구현
> 5. **미구현 상태(`List.of()` 반환)로 배포하면 필터링이 전혀 동작하지 않으므로 반드시 구현 후 배포할 것**

**CompressionQueryTransformer — 대화 히스토리 압축 (Stage 5 활용):**

```java
// Stage 5(Chat Memory)와 결합하면 효과적
// 예: "그 회사의 매출은?" → "A사의 2024년 3분기 매출은?"

QueryTransformer compressionTransformer = CompressionQueryTransformer.builder()
        .chatClientBuilder(chatClientBuilder)
        .build();

// RetrievalAugmentationAdvisor에 체이닝:
return RetrievalAugmentationAdvisor.builder()
        .queryTransformers(compressionTransformer, rewriteTransformer)
        // → 먼저 대화 맥락으로 압축 → 그 다음 검색 최적화로 변환
        .queryExpander(multiQueryExpander)
        .documentRetriever(documentRetriever)
        .build();
```

> **QueryTransformer 체이닝 순서:**
> 1. `CompressionQueryTransformer` — "그 회사" → "A사" (대화 맥락 해소)
> 2. `RewriteQueryTransformer` — "A사 매출 알려줘" → "A사 2024년 매출 실적" (검색 최적화)
>
> 순서가 중요하다. 맥락 해소를 먼저 하고, 그 결과를 검색에 최적화한다.

**FilterExpression 활용 예시:**

```java
// 문자열 기반 필터 (단순)
"source_file == 'A사_보고서.pdf'"

// 복합 조건 (AND)
"source_file == 'A사_보고서.pdf' && excerpt_keywords in ['마케팅', '비용']"

// 프로그래밍 방식 (FilterExpressionBuilder)
FilterExpressionBuilder b = new FilterExpressionBuilder();
b.and(
    b.eq("source_file", "A사_보고서.pdf"),
    b.in("excerpt_keywords", "마케팅", "비용")
).build();
```

### 지원 필터 연산자

| 연산자 | 설명 | 예시 |
|--------|------|------|
| `==` | 같음 | `"source_file == 'report.pdf'"` |
| `!=` | 다름 | `"source_file != 'draft.pdf'"` |
| `>`, `>=`, `<`, `<=` | 비교 | `"upload_date >= '2024-01-01'"` |
| `in` | 포함 | `"excerpt_keywords in ['마케팅','비용']"` |
| `nin` | 미포함 | `"status nin ['deleted']"` |
| `&&` | AND | `"a == 'x' && b == 'y'"` |
| `\|\|` | OR | `"a == 'x' \|\| a == 'y'"` |

> PgVectorStore에서 이 필터는 **PostgreSQL JSONB path 표현식**으로 변환되어 실행된다.

### API 엔드포인트 추가

| Method | Path | 기능 |
|--------|------|------|
| POST | `/api/rag/chat/filter` | 메타데이터 필터링 RAG 채팅 |

### 검증

```bash
# 1) 쿼리 변환 효과 확인 (로그에서 변환된 쿼리 출력)
curl -X POST http://localhost:8090/api/rag/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "그 회사 마케팅 비용이 왜 올랐어?"}'
# → 로그에서 "A사 3분기 마케팅 비용 증가 원인"으로 변환되는지 확인

# 2) 멀티 쿼리 확인 (로그에서 3개 확장 쿼리 출력)
# → 3개 쿼리가 생성되고, 각각의 검색 결과가 병합되는지 확인

# 3) 메타데이터 필터링 테스트
curl -X POST http://localhost:8090/api/rag/chat/filter \
  -H "Content-Type: application/json" \
  -d '{"message": "주요 내용 요약해줘", "sourceFile": "A사_보고서.pdf"}'
# → A사 보고서에서만 검색된 결과로 답변하는지 확인

# 4) 필터 없이 같은 질문 → 여러 문서가 섞여서 답변하는지 비교
curl -X POST http://localhost:8090/api/rag/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "주요 내용 요약해줘"}'
```

---

## 6. Stage 12: Reranker (검색 결과 재정렬)

### 목표

벡터 검색으로 가져온 후보군을 **LLM 기반 Reranker로 재정렬**하여, 실제로 질문에 답할 수 있는 청크만 상위에 올린다.

### 왜 필요한가

벡터 유사도 검색의 한계:
```
질문: "A사 3분기 마케팅 비용이 왜 증가했나?"

벡터 검색 top-5 결과:
1위 (유사도 0.82): "마케팅 비용이 전 분기 대비 15% 증가했다"     ← 원인이 아님 (사실만 기술)
2위 (유사도 0.78): "B사도 마케팅 예산을 확대하는 추세이다"       ← 다른 회사
3위 (유사도 0.75): "디지털 광고 단가 상승이 주요 원인이다"       ← 정답!
4위 (유사도 0.71): "마케팅 팀 인원이 10명 증원되었다"           ← 부분 원인
5위 (유사도 0.68): "비용 절감 방안을 모색 중이다"               ← 관련 낮음

Reranker 적용 후:
1위: "디지털 광고 단가 상승이 주요 원인이다"       ← 정답이 1위로!
2위: "마케팅 팀 인원이 10명 증원되었다"           ← 부분 원인
3위: "마케팅 비용이 전 분기 대비 15% 증가했다"     ← 배경 정보
```

Reranker는 단순 벡터 유사도가 아닌, **"이 청크가 이 질문에 실제로 답할 수 있는가?"** 를 LLM이 판단한다.

### 학습 포인트
- `DocumentPostProcessor` 인터페이스 이해
- LLM 기반 Reranker 직접 구현 (Spring AI에 내장 Reranker 없음)
- `RetrievalAugmentationAdvisor`에 후처리기로 연결
- 넓게 검색(topK=15) → Reranker로 좁히기(top=5) 전략

### 생성/수정 파일 (2개 생성, 1개 수정)

| 파일 | 변경 내용 |
|------|-----------|
| `rag/LlmRerankerPostProcessor.java` | **생성** — LLM 기반 Reranker 구현 |
| `config/RerankerConfig.java` | **생성** — Reranker 빈 설정 |
| `config/RagAdvisorConfig.java` | RetrievalAugmentationAdvisor에 Reranker 연결 |

### 핵심 코드 패턴

**LlmRerankerPostProcessor — LLM 기반 Reranker:**

```java
@Slf4j
public class LlmRerankerPostProcessor implements DocumentPostProcessor {

    private final ChatClient chatClient;
    private final int topN;

    public LlmRerankerPostProcessor(ChatClient.Builder chatClientBuilder, int topN) {
        this.topN = topN;
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                    당신은 문서 관련성 평가 전문가입니다.
                    주어진 질문에 대해 각 문서가 얼마나 관련이 있는지 0~10 점수로 평가하세요.
                    반드시 JSON 배열 형식으로만 응답하세요.
                    """)
                .build();
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents.isEmpty()) {
            return documents;
        }

        log.info("Reranking 시작: {} 문서를 top-{}으로 재정렬", documents.size(), topN);

        // 1) 각 문서의 내용을 번호와 함께 구성
        StringBuilder docsText = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            docsText.append("[문서 %d]: %s\n\n".formatted(i, documents.get(i).getContent()));
        }

        // 2) LLM에게 관련성 점수 요청
        String prompt = """
                질문: %s

                아래 문서들의 관련성을 평가하세요:

                %s

                각 문서의 관련성 점수를 0~10으로 매기고, 다음 JSON 형식으로만 응답하세요:
                [{"index": 0, "score": 8}, {"index": 1, "score": 3}, ...]
                """.formatted(query.text(), docsText.toString());

        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        // 3) 점수 파싱 후 상위 N개 반환
        List<ScoredDocument> scored = parseScores(response, documents);
        scored.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());

        List<Document> reranked = scored.stream()
                .limit(topN)
                .map(ScoredDocument::document)
                .toList();

        log.info("Reranking 완료: {} → {} 문서", documents.size(), reranked.size());
        return reranked;
    }

    private List<ScoredDocument> parseScores(String response, List<Document> documents) {
        // JSON 파싱 로직 (ObjectMapper 사용)
        // 파싱 실패 시 원본 순서 유지
        try {
            ObjectMapper mapper = new ObjectMapper();
            // response에서 JSON 배열 부분만 추출
            String json = response.substring(
                    response.indexOf('['), response.lastIndexOf(']') + 1);
            List<Map<String, Object>> scores = mapper.readValue(json,
                    new TypeReference<>() {});

            return scores.stream()
                    .map(s -> new ScoredDocument(
                            documents.get(((Number) s.get("index")).intValue()),
                            ((Number) s.get("score")).doubleValue()))
                    .toList();
        } catch (Exception e) {
            log.warn("Reranker 점수 파싱 실패, 원본 순서 유지: {}", e.getMessage());
            return IntStream.range(0, documents.size())
                    .mapToObj(i -> new ScoredDocument(documents.get(i), documents.size() - i))
                    .toList();
        }
    }

    private record ScoredDocument(Document document, double score) {}
}
```

**RerankerConfig — Reranker 빈 설정:**

```java
@Configuration
public class RerankerConfig {

    @Bean
    public LlmRerankerPostProcessor llmReranker(ChatClient.Builder chatClientBuilder) {
        return new LlmRerankerPostProcessor(chatClientBuilder, 5);
        // 상위 5개만 최종 반환
    }
}
```

**RagAdvisorConfig — Reranker 연결:**

```java
@Bean
public Advisor retrievalAugmentationAdvisor(
        ChatClient.Builder chatClientBuilder,
        LlmRerankerPostProcessor reranker) {

    QueryTransformer rewriteTransformer = RewriteQueryTransformer.builder()
            .chatClientBuilder(chatClientBuilder)
            .build();

    QueryExpander multiQueryExpander = MultiQueryExpander.builder()
            .chatClientBuilder(chatClientBuilder)
            .numberOfQueries(3)
            .includeOriginal(true)
            .build();

    DocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
            .vectorStore(vectorStore)
            .similarityThreshold(0.2)    // 넓게 검색 (Reranker가 걸러줌)
            .topK(15)                     // 15개 후보 → Reranker → 5개
            .build();

    return RetrievalAugmentationAdvisor.builder()
            .queryTransformers(rewriteTransformer)
            .queryExpander(multiQueryExpander)
            .documentRetriever(documentRetriever)
            .documentPostProcessors(reranker)    // ← Reranker 추가
            .build();
}
```

### 전체 검색 파이프라인 (Stage 10~12 통합)

```
사용자 질문: "A사 마케팅 비용 왜 올랐어?"
       │
       ▼
[Stage 11] RewriteQueryTransformer
       → "A사 3분기 마케팅 비용 증가 원인 분석"
       │
       ▼
[Stage 11] MultiQueryExpander (3개)
       → Q1: "A사 마케팅 비용 증가 원인"
       → Q2: "A사 마케팅 예산 변동 분석"
       → Q3: "A사 비용 상승 요인"
       │
       ▼
[Stage 10] VectorStore 검색 (메타데이터 활용)
       → 3개 쿼리 × topK=15 → 중복 제거 → 약 20~30개 후보
       → excerpt_keywords, section_summary로 더 정확한 매칭
       │
       ▼
[Stage 12] LLM Reranker
       → 20~30개 후보를 점수화 → 상위 5개만 선택
       │
       ▼
[공통] LLM 답변 생성
       → 정제된 5개 청크로 최종 답변
```

### 검증

```bash
# 1) Reranker 동작 확인 (로그에서 점수/재정렬 확인)
curl -X POST http://localhost:8090/api/rag/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "마케팅 비용이 왜 증가했어?"}'
# → 로그: "Reranking 시작: 15 문서를 top-5으로 재정렬"
# → 로그: "Reranking 완료: 15 → 5 문서"

# 2) Reranker 적용 전후 답변 품질 비교
#    같은 질문에 대해:
#    - 이전: 관련 없는 정보가 섞인 답변
#    - 이후: 핵심 원인만 정확히 답변

# 3) 복잡한 질문 테스트
curl -X POST http://localhost:8090/api/rag/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "A사와 B사의 마케팅 전략 차이점은?"}'
# → 두 회사 관련 청크가 각각 정확히 선별되는지 확인
```

### 비용 주의사항

| 항목 | 추가 API 호출 | 설명 |
|------|-------------|------|
| RewriteQueryTransformer | 1회 | 쿼리 변환 |
| MultiQueryExpander | 1회 | 3개 쿼리 생성 |
| LLM Reranker | 1회 | 15개 문서 점수화 |
| **합계** | **질문당 약 3회 추가 호출** | 검색 품질 향상 대가 |

---

## 7. Stage 13: Agentic RAG (자율 검색 에이전트)

### 목표

LLM이 **스스로 검색 전략을 판단하고, 필요하면 재검색하는** Agentic RAG를 구현한다.
"한 번 검색하고 끝"이 아니라, 사람이 검색하는 것처럼 **탐색 → 평가 → 재탐색** 루프를 돈다.

### 왜 필요한가

Stage 12까지의 한계:
```
질문: "A사의 3분기 매출은 전년 대비 몇 % 변했고, 그 원인은?"

Stage 12 파이프라인:
  검색 → Reranker → top-5 반환 → LLM 답변

  문제: 한 번의 검색으로 "매출 수치" + "변동 원인"을 둘 다 찾기 어려움
        한쪽만 찾으면 불완전한 답변이 됨
```

Agentic RAG:
```
질문: "A사의 3분기 매출은 전년 대비 몇 % 변했고, 그 원인은?"

에이전트 동작:
  1차 검색: "A사 3분기 매출 전년 대비" → 매출 수치 확보
  에이전트 판단: "수치는 찾았지만 원인이 부족"
  2차 검색: "A사 3분기 매출 변동 원인 분석" → 원인 확보
  에이전트 판단: "충분한 정보 확보됨"
  → 종합 답변 생성
```

### 학습 포인트
- 검색을 `@Tool`로 노출하여 LLM이 직접 호출하게 하는 패턴
- `internalToolExecutionEnabled(false)`로 도구 실행 제어
- 검색 결과 평가 → 재검색 루프 구현
- `ToolCallingManager`로 도구 호출 흐름 수동 관리
- 최대 호출 횟수 제한 (`maxToolCalls`)으로 무한 루프 방지

### 생성/수정 파일 (4개 생성, 2개 수정)

| 파일 | 변경 내용 |
|------|-----------|
| `tool/KnowledgeSearchTool.java` | **생성** — 벡터 검색을 @Tool로 노출 |
| `tool/MetadataSearchTool.java` | **생성** — 메타데이터 필터링 검색 도구 |
| `service/AgenticRagService.java` | **생성** — Agentic RAG 서비스 |
| `controller/AgenticRagController.java` | **생성** — Agentic RAG 엔드포인트 |
| `config/ChatClientConfig.java` | Agentic 전용 ChatClient 등록 |
| `static/js/app.js` | Agentic RAG 모드 UI 추가 |

### 핵심 코드 패턴

**KnowledgeSearchTool — 벡터 검색 도구:**

```java
@Component
@RequiredArgsConstructor
public class KnowledgeSearchTool {

    private final VectorStore vectorStore;

    @Tool(description = """
            내부 문서 데이터베이스에서 정보를 검색합니다.
            사용자 질문에 답하기 위해 관련 문서를 찾을 때 사용하세요.
            검색 결과가 부족하면 다른 검색어로 다시 검색할 수 있습니다.
            """)
    public String searchDocuments(
            @ToolParam(description = "검색할 쿼리 (구체적이고 명확할수록 좋습니다)")
            String query) {

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(5)
                        .similarityThreshold(0.3)
                        .build());

        if (results.isEmpty()) {
            return "검색 결과가 없습니다. 다른 검색어로 시도해보세요.";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            sb.append("--- 문서 %d ---\n".formatted(i + 1));
            sb.append("출처: %s\n".formatted(doc.getMetadata().getOrDefault("source_file", "알 수 없음")));
            sb.append("키워드: %s\n".formatted(doc.getMetadata().getOrDefault("excerpt_keywords", "")));
            sb.append("내용: %s\n\n".formatted(doc.getContent()));
        }
        return sb.toString();
    }
}
```

**MetadataSearchTool — 메타데이터 필터링 검색:**

```java
@Component
@RequiredArgsConstructor
public class MetadataSearchTool {

    private final VectorStore vectorStore;

    @Tool(description = """
            특정 파일이나 키워드로 범위를 좁혀서 검색합니다.
            이미 어떤 파일에 정보가 있는지 알 때, 또는 특정 키워드가 포함된 문서만 찾을 때 사용하세요.
            """)
    public String searchByMetadata(
            @ToolParam(description = "검색할 쿼리") String query,
            @ToolParam(description = "파일명 필터 (예: 'A사_보고서.pdf'). 필터 없으면 빈 문자열") String sourceFile) {

        SearchRequest.Builder requestBuilder = SearchRequest.builder()
                .query(query)
                .topK(5)
                .similarityThreshold(0.3);

        // FilterExpressionBuilder + 화이트리스트 검증으로 안전하게 필터 생성
        if (sourceFile != null && !sourceFile.isBlank()) {
            // 실제 저장된 파일 목록과 대조 (sanitize 대신 정확 일치 검증)
            // → 특수문자 제거 방식은 저장된 파일명과 불일치할 수 있으므로 사용하지 않음
            List<String> knownFiles = getKnownSourceFiles();
            if (knownFiles.contains(sourceFile)) {
                FilterExpressionBuilder b = new FilterExpressionBuilder();
                requestBuilder.filterExpression(b.eq("source_file", sourceFile).build());
            } else {
                return "요청한 파일명('%s')이 등록된 문서 목록에 없습니다.".formatted(sourceFile);
            }
        }

        List<Document> results = vectorStore.similaritySearch(requestBuilder.build());

        if (results.isEmpty()) {
            return "해당 조건으로 검색된 결과가 없습니다.";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            sb.append("--- 문서 %d ---\n".formatted(i + 1));
            sb.append("출처: %s\n".formatted(doc.getMetadata().getOrDefault("source_file", "알 수 없음")));
            sb.append("내용: %s\n\n".formatted(doc.getContent()));
        }
        return sb.toString();
    }

    /**
     * ⚠️ RagService.getKnownSourceFiles()와 동일하게 반드시 구현 필요.
     * 미구현 시 모든 sourceFile 필터가 실패하여 항상 "등록된 문서 목록에 없습니다" 반환.
     * 공통 유틸로 추출하여 RagService와 공유하는 것을 권장.
     */
    private List<String> getKnownSourceFiles() {
        // RagService.getKnownSourceFiles()와 동일 구현 — 공통 유틸로 추출 권장
        throw new UnsupportedOperationException("getKnownSourceFiles() 미구현");
    }
}
```

**AgenticRagService — 자율 검색 에이전트:**

```java
@Service
@Slf4j
public class AgenticRagService {

    private static final int MAX_TOOL_CALLS = 5;

    private final ChatClient agenticChatClient;

    public AgenticRagService(
            ChatClient.Builder chatClientBuilder,
            KnowledgeSearchTool knowledgeSearchTool,
            MetadataSearchTool metadataSearchTool) {

        this.agenticChatClient = chatClientBuilder
                .defaultSystem("""
                    당신은 내부 문서를 검색하여 정확하게 답변하는 AI 어시스턴트입니다.

                    ## 행동 규칙
                    1. 사용자 질문을 분석하고, 필요한 정보를 검색 도구로 찾으세요.
                    2. 한 번의 검색으로 충분하지 않으면, 다른 검색어로 추가 검색하세요.
                    3. 여러 관점에서 검색하여 종합적인 답변을 만드세요.
                    4. 검색 결과에 답이 없으면, 솔직하게 "문서에서 찾을 수 없다"고 하세요.
                    5. 답변 시 출처(파일명)를 반드시 명시하세요.
                    6. 도구 호출은 최대 5회까지 가능합니다. 효율적으로 검색하세요.

                    ## 검색 전략 가이드
                    - 먼저 넓은 검색어로 시작하세요
                    - 결과가 부족하면 구체적인 키워드로 좁혀서 재검색하세요
                    - 비교 질문은 각 대상을 별도로 검색하세요
                    - 특정 파일에 대한 질문이면 메타데이터 검색을 사용하세요
                    """)
                .defaultTools(knowledgeSearchTool, metadataSearchTool)
                .build();
    }

    public String chat(String message, String conversationId) {
        log.info("Agentic RAG 시작: {}", message);

        String answer = agenticChatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .options(ToolCallingChatOptions.builder()
                        .maxToolCalls(MAX_TOOL_CALLS)   // 무한 루프 방지: 최대 5회
                        .build())
                .call()
                .content();

        log.info("Agentic RAG 완료");
        return answer;
    }
}
```

> **무한 루프 방지 메커니즘:**
> - `maxToolCalls(5)`: Spring AI가 도구 호출 횟수를 추적하여 5회 초과 시 자동 중단
> - 시스템 프롬프트에도 "최대 5회"를 명시하여 LLM이 효율적으로 검색하도록 유도
> - 5회 초과 시 Spring AI가 현재까지 수집된 정보로 답변 생성을 강제
>
> **핵심 차이점:**
> - Stage 11~12: `RetrievalAugmentationAdvisor`가 정해진 순서로 검색 → 변환 → 재정렬
> - Stage 13: **LLM이 직접 판단**하여 어떤 도구를, 어떤 쿼리로, 몇 번 호출할지 결정

**AgenticRagController — 엔드포인트:**

```java
@RestController
@RequiredArgsConstructor
public class AgenticRagController {

    private final AgenticRagService agenticRagService;

    @PostMapping("/api/agentic/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String answer = agenticRagService.chat(
                request.message(), request.conversationId());
        return new ChatResponse(answer);
    }
}
```

### Agentic RAG의 동작 시나리오

```
시나리오 1: 단순 질문
━━━━━━━━━━━━━━━━━━━━━━
질문: "A사 3분기 매출은?"

LLM 판단: "매출 데이터를 찾아야 함"
  → searchDocuments("A사 3분기 매출") 호출
  → 결과 확인: 매출 정보 발견
LLM 판단: "충분한 정보"
  → 답변 생성 (도구 1회 호출)


시나리오 2: 복합 질문
━━━━━━━━━━━━━━━━━━━━━━
질문: "A사와 B사의 마케팅 전략 차이점은?"

LLM 판단: "두 회사를 각각 검색해야 함"
  → searchDocuments("A사 마케팅 전략") 호출
  → 결과 확인: A사 정보 확보
  → searchDocuments("B사 마케팅 전략") 호출
  → 결과 확인: B사 정보 확보
LLM 판단: "두 회사 정보 모두 확보"
  → 비교 분석 답변 생성 (도구 2회 호출)


시나리오 3: 탐색적 질문
━━━━━━━━━━━━━━━━━━━━━━
질문: "최근 보고서에서 가장 우려되는 점은?"

LLM 판단: "전체적으로 탐색해야 함"
  → searchDocuments("리스크 요인 우려 사항") 호출
  → 결과 확인: 재무 리스크 발견, 다른 리스크도 있을 수 있음
  → searchDocuments("운영 리스크 문제점") 호출
  → 결과 확인: 운영 리스크 추가 발견
  → searchByMetadata("우려 전망", "A사_보고서.pdf") 호출
  → 결과 확인: A사 특정 우려 사항 발견
LLM 판단: "다양한 관점의 정보 충분"
  → 종합 분석 답변 생성 (도구 3회 호출)
```

### API 엔드포인트 추가

| Method | Path | 기능 |
|--------|------|------|
| POST | `/api/agentic/chat` | Agentic RAG 채팅 |

### 검증

```bash
# 1) 단순 질문 — 1회 검색으로 충분한 경우
curl -X POST http://localhost:8090/api/agentic/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "A사 3분기 매출은 얼마야?"}'
# → 로그에서 도구 호출 1~2회 확인

# 2) 복합 질문 — 여러 번 검색이 필요한 경우
curl -X POST http://localhost:8090/api/agentic/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "A사와 B사의 마케팅 전략 차이점을 비교 분석해줘"}'
# → 로그에서 도구 호출 2~3회 확인
# → 답변에 두 회사 정보가 모두 포함되는지 확인

# 3) 정보 부족 상황 — 적절히 포기하는지
curl -X POST http://localhost:8090/api/agentic/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "C사의 2025년 실적 전망은?"}'
# → C사 관련 문서가 없으면 "문서에서 찾을 수 없습니다" 응답

# 4) 기존 RAG 엔드포인트와 답변 품질 비교
#    같은 질문을 /api/rag/chat과 /api/agentic/chat에 각각 보내서
#    답변의 정확도/완전성 비교
```

### Naive RAG vs Agentic RAG 최종 비교

```
질문: "A사와 B사의 3분기 매출을 비교하고, 차이 원인을 분석해줘"

[Naive RAG] (Stage 3)
  → 벡터 검색 1회 → top-5 청크 → 답변
  → 문제: A사/B사 정보가 섞이고, 원인 분석 청크를 놓칠 가능성 높음

[Advanced RAG] (Stage 12)
  → 쿼리 변환 → 멀티 쿼리(3개) → 검색 → Reranker → top-5 → 답변
  → 개선: 더 정확한 검색, 관련 없는 청크 제거
  → 한계: 여전히 한 번의 검색 사이클, 복합 질문에 약함

[Agentic RAG] (Stage 13)
  → LLM이 판단:
    1차: "A사 3분기 매출" 검색 → A사 매출 확보
    2차: "B사 3분기 매출" 검색 → B사 매출 확보
    3차: "A사 B사 매출 차이 원인" 검색 → 원인 분석 확보
  → 모든 정보를 종합하여 완전한 비교 분석 답변
```

---

## 8. 단계별 파일 변경 요약

### Stage 10 (3개 생성, 2개 수정)
- **생성:** `EtlPipelineConfig.java`, `AsyncConfig.java`, `DocumentEnrichmentService.java`
- **수정:** `DocumentService.java`, `application.properties`

### Stage 11 (2개 생성, 2개 수정, 1개 의존성 추가)
- **의존성:** `spring-ai-rag`
- **생성:** `RagAdvisorConfig.java`, `RagChatRequest.java`
- **수정:** `RagService.java`, `RagController.java`

### Stage 12 (2개 생성, 1개 수정)
- **생성:** `LlmRerankerPostProcessor.java`, `RerankerConfig.java`
- **수정:** `RagAdvisorConfig.java`

### Stage 13 (4개 생성, 2개 수정)
- **생성:** `KnowledgeSearchTool.java`, `MetadataSearchTool.java`, `AgenticRagService.java`, `AgenticRagController.java`
- **수정:** `ChatClientConfig.java`, `app.js`

---

## 9. 전체 API 엔드포인트 총정리

### 기존 (Stage 1~9)

| Method | Path | 기능 | Stage |
|--------|------|------|-------|
| POST | `/api/chat` | 일반 Gemini 채팅 | 1 |
| POST | `/api/chat/stream` | 일반 채팅 스트리밍 | 4 |
| POST | `/api/documents` | 문서 업로드 ETL | 2 → **10에서 강화** |
| POST | `/api/rag/chat` | RAG 기반 채팅 | 2 → **11에서 고도화** |
| POST | `/api/rag/chat/stream` | RAG 채팅 스트리밍 | 4 |

### 신규 (Stage 10~13)

| Method | Path | 기능 | Stage |
|--------|------|------|-------|
| GET | `/api/documents/{jobId}/status` | 비동기 인제스트 상태 조회 | 10 |
| POST | `/api/rag/chat/filter` | 메타데이터 필터링 RAG | 11 |
| POST | `/api/agentic/chat` | Agentic RAG 채팅 | 13 |

---

## 10. RAG 품질 평가 프레임워크

### 왜 필요한가

각 Stage의 고도화가 실제로 효과가 있는지 **정량적으로 증명**해야 한다.
로그 확인/수동 비교만으로는 회귀 방지가 불가능하고, 튜닝 방향을 잡기 어렵다.

### 평가셋 구성

Stage 10 시작 전에 **오프라인 평가셋(Golden Dataset)**을 먼저 만든다.

```
resources/eval/golden-dataset.json
[
  {
    "question": "A사의 3분기 마케팅 비용은 전 분기 대비 얼마나 증가했나?",
    "expected_answer": "15% 증가",
    "ground_truth_source": "A사_3분기_보고서.pdf",
    "category": "사실 조회"
  },
  {
    "question": "A사와 B사의 마케팅 전략 차이점은?",
    "expected_answer": "A사는 디지털 광고 중심, B사는 오프라인 마케팅 중심",
    "ground_truth_source": "A사_3분기_보고서.pdf, B사_3분기_보고서.pdf",
    "category": "비교 분석"
  }
]
```

> **평가셋 크기:** 최소 20~30개 질문. 카테고리별(사실 조회, 비교, 요약, 추론 등) 골고루 분포.
> **중요:** 평가셋은 Stage별로 동일하게 사용해야 비교가 의미 있다.

### 평가 지표

| 지표 | 측정 방법 | 의미 | 목표 |
|------|----------|------|------|
| **Recall@k** | 검색된 top-k 청크 중 정답 소스 포함 비율 | 필요한 문서를 잘 찾는가? | Stage별 개선 |
| **Groundedness** | LLM 답변이 검색된 청크에 근거하는지 여부 | 환각 없이 답변하는가? | 90% 이상 |
| **Answer Correctness** | 답변이 expected_answer와 의미적으로 일치하는지 | 최종 답변이 맞는가? | Stage별 개선 |
| **Latency (p50/p95)** | 질문 → 답변까지 소요 시간 | 사용자 체감 속도 | p95 < 10초 |
| **Cost per Query** | 질문당 LLM API 호출 횟수 및 토큰 사용량 | 운영 비용 | 모니터링 |

### Stage별 게이트 (통과 기준)

| Stage | 핵심 게이트 | 측정 방법 |
|-------|-----------|----------|
| Stage 10 | Recall@5 ≥ 기존 대비 +10% | 동일 평가셋으로 검색 결과 비교 |
| Stage 11 | Recall@5 ≥ Stage 10 대비 +15% | 쿼리 변환/멀티 쿼리 효과 측정 |
| Stage 12 | Answer Correctness ≥ Stage 11 대비 +10% | Reranker가 정답 청크를 상위로 올리는지 |
| Stage 13 | 복합 질문 Answer Correctness ≥ 80% | 단순 질문은 Stage 12와 동등, 복합 질문에서 우위 |

### 평가 실행 방법

```bash
# 평가 스크립트 (간이 버전 — curl 기반)
#!/bin/bash
EVAL_FILE="src/main/resources/eval/golden-dataset.json"
ENDPOINT="http://localhost:8090/api/rag/chat"
RESULTS_FILE="eval-results-$(date +%Y%m%d-%H%M%S).json"

# jq로 각 질문을 순회하면서 API 호출 + 응답 시간 측정
jq -c '.[]' $EVAL_FILE | while read item; do
  question=$(echo $item | jq -r '.question')
  start_time=$(date +%s%N)

  answer=$(curl -s -X POST $ENDPOINT \
    -H "Content-Type: application/json" \
    -d "{\"message\": \"$question\"}" | jq -r '.answer')

  end_time=$(date +%s%N)
  latency_ms=$(( (end_time - start_time) / 1000000 ))

  echo "{\"question\": \"$question\", \"answer\": \"$answer\", \"latency_ms\": $latency_ms}" >> $RESULTS_FILE
done

echo "평가 완료: $RESULTS_FILE"
```

> **고급 평가:** Spring AI의 `Evaluator` 인터페이스나 RAGAS, DeepEval 같은 프레임워크를 도입하면
> Groundedness, Faithfulness 등을 자동으로 측정할 수 있다. 학습 범위에서는 위 간이 버전으로 시작.

---

## 11. 잠재적 이슈 및 대응

| 이슈 | 대응 |
|------|------|
| Enricher의 LLM API 호출 비용 | 중요 문서만 선택적 Enricher 적용, 배치 처리 고려 |
| Enricher 처리 시간 (청크당 수 초) | `@Async` 비동기 인제스트 + 상태 조회 API 패턴 적용 |
| 필터 식 인젝션 위험 | `String.format` 금지, `FilterExpressionBuilder` 공개 API만 사용 + 입력값 화이트리스트 검증(`getKnownSourceFiles()` 정확 일치) |
| excerpt_keywords 스키마 불일치 | KeywordEnricher 결과를 `List<String>`으로 변환 후 저장 (콤마 문자열 → JSON 배열) |
| Agentic RAG 무한 루프 | `maxToolCalls(5)`를 실제 호출 경로에 강제 적용 + 시스템 프롬프트에 횟수 명시 |
| MultiQueryExpander 중복 결과 | Spring AI가 내부적으로 중복 제거 처리 |
| LLM Reranker 점수 파싱 실패 | JSON 파싱 실패 시 원본 순서 유지 (fallback) |
| Agentic RAG 응답 지연 | 도구 호출 횟수 × LLM 응답 시간, Streaming과 결합 권장 |
| 기존 벡터 데이터와 호환 | Stage 10 적용 시 기존 데이터 재인제스트 필요 |
| QueryTransformer 체이닝 순서 | Compression(맥락 해소) → Rewrite(검색 최적화) 순서 준수 |
| 고도화 효과 증명 어려움 | 평가셋(Golden Dataset) + Recall@k, Groundedness 지표로 Stage별 정량 검증 |

---

## 12. RAG 진화 단계 총정리

```
Stage 1~3   [Naive RAG]
            질문 → 벡터 검색 1회 → top-5 → LLM 답변
            ✅ 기본 동작  ❌ 검색 품질 낮음

Stage 10    [Enhanced ETL]
            청크에 키워드/요약/출처 메타데이터 자동 부여
            ✅ 검색 재료 품질↑  ❌ 검색 방식은 동일

Stage 11    [Smart Search]
            쿼리 변환 + 멀티 쿼리 + 메타데이터 필터링
            ✅ 검색 방식 다양화  ❌ 결과 검증 없음

Stage 12    [Reranked RAG]
            넓게 검색 → LLM이 관련성 재평가 → 상위만 사용
            ✅ 검색 결과 정제  ❌ 여전히 1회 검색 사이클

Stage 13    [Agentic RAG]
            LLM이 직접 검색 전략 수립 → 실행 → 평가 → 재검색
            ✅ 사람처럼 탐색  ✅ 복합 질문 대응
```
