## 프로젝트 개요

- Spring AI RAG 챗봇 프로토타입 (회사 서비스 도입 목적)
- Spring Boot 3.5.10 + Spring AI 1.1.2 + Java 21
- LLM/임베딩: Google Gemini (gemini-2.0-flash, text-embedding-004)
- 벡터 DB: PostgreSQL pgvector

## 기술 스택

- 빌드: Gradle 8.14.4
- 패키지: `com.example.ai`
- 주요 의존성: spring-ai-starter-model-google-genai, lombok

## 설계 문서

- 구현 계획서: `docs/spring-ai-rag-설계서.md`

## 코드 컨벤션

- Controller → Service → Repository 계층 구조
- DTO는 `dto` 패키지에 분리
- 설정 클래스는 `config` 패키지에 분리
- API 키는 환경변수로 주입 (하드코딩 금지)
