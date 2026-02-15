## 프로젝트 개요

- Spring AI RAG 챗봇 프로토타입 (회사 서비스 도입 목적)
- Spring Boot 3.5.10 + Spring AI 1.1.2 + Java 21
- LLM/임베딩: Google Gemini (gemini-3-flash-preview, gemini-embedding-001)
- 벡터 DB: PostgreSQL pgvector (Docker Compose)

## 기술 스택

- 빌드: Gradle 8.14.4
- 패키지: `com.example.ai`
- 주요 의존성: spring-ai-starter-model-google-genai, lombok

## 설계 문서

- 구현 계획서: `docs/spring-ai-rag-설계서.md`

## Git 브랜치 전략

- **반드시 master 브랜치에서 직접 작업한다** (별도 브랜치/worktree 생성 금지)
- 커밋은 기능 단위로 작게 쪼개서 한다
- 커밋 메시지는 변경 내용을 명확히 기술한다
- **커밋 메시지에 Co-Authored-By 등 AI 도구 흔적을 포함하지 않는다**

## 코드 컨벤션

- Controller → Service → Repository 계층 구조
- DTO는 `dto` 패키지에 분리
- 설정 클래스는 `config` 패키지에 분리
- API 키는 환경변수로 주입 (하드코딩 금지)

## 진행 상황

- **Stage 1 ✅**: 기본 Gemini 챗봇 (POST /api/chat + 웹 UI)
- **Stage 2 ✅**: SimpleVectorStore 기반 RAG (문서 업로드 ETL + RAG 채팅)
- **Stage 3 ✅**: PgVector 통합 (Docker Compose + 영속적 벡터 저장)

## API 엔드포인트

| Method | Path | 기능 | Stage |
|--------|------|------|-------|
| POST | `/api/chat` | 일반 Gemini 채팅 | 1 |
| POST | `/api/documents` | 문서 업로드 ETL | 2 |
| POST | `/api/rag/chat` | RAG 기반 채팅 | 2 |

## 런타임 설정

- 서버 포트: 8090
- 임베딩 차원: 3072
- RAG similarityThreshold: 0.3, topK: 5
- Docker: `docker compose up -d` 로 PostgreSQL 시작 필요
