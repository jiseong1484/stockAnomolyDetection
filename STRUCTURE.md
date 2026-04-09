# 📂 프로젝트 디렉토리 구조 및 아키텍처 가이드

본 프로젝트는 **실시간 고성능 스트림 처리**를 목표로 하며, 유지보수가 용이하고 확장 가능한 **객체지향 설계(SOLID)** 및 **클린 아키텍처** 원칙을 따릅니다.

---

## 🏗️ 전체 구조 (System Overview)

```text
stock-anomaly-detection/
├── api-server/                # [Java/Spring Boot] 데이터 수집 및 클라이언트 알림 서비스
├── web-app/                   # [Next.js] 실시간 모니터링 대시보드 및 알림 UI
├── processing-engine/         # [C++] 실시간 이상치 탐지 엔진 (Ring Buffer, SIMD 최적화)
├── shared/                    # [Proto] 서비스 간 데이터 교환용 Protocol Buffers 규격
├── infra/                     # [Docker] Kafka, Redis, InfluxDB, MySQL 인프라 구성
├── scripts/                   # 유틸리티 및 환경 설정 스크립트
└── docs/                      # 설계 문서 및 API 명세
```

---

## 🍃 API Server (Spring Boot) - 설계 원칙

유지보수가 용이하도록 **계층화된 아키텍처(Layered Architecture)** 및 **도메인 중심 설계(DDD)** 개념을 적용합니다.

- **`domain/`**: 비즈니스 핵심 로직과 엔티티 (외부 라이브러리 의존성 배제)
- **`application/`**: 유즈케이스 처리 및 비즈니스 워크플로우 (Service)
- **`infrastructure/`**: 데이터베이스(JPA/Redis), Kafka 연동 등 외부 인터페이스 구현체
- **`web/`**: REST API 컨트롤러, WebSocket/SSE 핸들러
- **`common/`**: 전역 예외 처리, 유틸리티 등

> **핵심 원칙**: 
> 1. 인터페이스 기반 설계를 통한 느슨한 결합 (Dependency Inversion)
> 2. DTO(Data Transfer Object)와 Entity의 엄격한 분리
> 3. 모든 비즈니스 예외는 커스텀 예외로 정의 및 전역 핸들링

---

## ⚡ Processing Engine (C++) - 설계 원칙

성능 최적화와 동시에 가독성을 유지하기 위해 **모던 C++(C++17/20)** 및 **RAII(Resource Acquisition Is Initialization)** 패턴을 사용합니다.

- **`include/`**: 인터페이스 및 템플릿 정의 (.h)
- **`src/`**: 구현부 (.cpp)
- **`tests/`**: Google Test(gtest)를 활용한 단위 테스트
- **구성 요소**:
  - `KafkaConsumer`: 실시간 메시지 수신 (librdkafka 기반)
  - `Detector`: 이상치 탐지 알고리즘 (Z-score, EWMA 등)
  - `SlidingWindow`: 효율적인 데이터 관리를 위한 Ring Buffer 구현
  - `AlertPublisher`: 탐지된 이상치를 Kafka로 발행

---

## 📡 Shared (Protocol Buffers)

Java와 C++ 간의 **데이터 직렬화 효율성**과 **정합성**을 위해 Protocol Buffers를 사용합니다.

- `stock_event.proto`: 주가 틱 데이터 및 이상치 알림 메시지 규격 정의

---

## 🛡️ 유지보수 및 확장성 전략

1. **테스트 주도**: 모든 핵심 비즈니스 로직은 단위 테스트를 포함해야 합니다.
2. **모니터링**: Prometheus + Grafana를 통해 지연 시간(Latency) 및 처리량(Throughput)을 실시간 추적합니다.
3. **병렬성**: Kafka Partition 기반으로 C++ 엔진 인스턴스를 수평 확장할 수 있는 구조를 유지합니다.
