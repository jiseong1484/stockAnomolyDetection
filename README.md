# 실시간 주식 이상 탐지 시스템 (Stock Anomaly Detection System)

본 프로젝트는 대용량 실시간 주식 데이터를 스트리밍으로 처리하여 급격한 변동성이나 이상 징후를 밀리초(ms) 단위의 지연으로 탐지하고 사용자에게 실시간 알림을 제공하는 시스템입니다.

## 🚀 주요 특징

- **실시간 스트림 처리**: Kafka 기반의 이벤트 드리븐 아키텍처를 통한 저지연 데이터 처리
- **고성능 엔진**: C++ 워커를 활용한 CPU 집약적 연산(Ring Buffer, Z-score 알고리즘) 처리
- **시계열 데이터 최적화**: InfluxDB를 활용하여 밀리초 단위의 틱 데이터를 효율적으로 저장 및 분석
- **실시간 대시보드**: Next.js와 SSE(Server-Sent Events)를 활용한 지연 없는 모니터링 UI
- **확장성**: Kafka 파티셔닝을 통한 탐지 엔진의 수평적 확장 가능 구조

## 🛠 기술 스택

### Backend (API Server)
- **Framework**: Java 17, Spring Boot 3.2.4
- **Security**: Spring Security, JWT (Json Web Token)
- **Messaging**: Apache Kafka 3.7.0
- **Database**: MySQL 8.0 (사용자/설정), InfluxDB (시계열 틱), Redis (캐싱/실시간 랭킹)

### Frontend (Web App)
- **Framework**: Next.js 16 (App Router), React 19, TypeScript
- **Styling**: TailwindCSS 4.0, Shadcn/UI
- **Charts**: Recharts (실시간 주가 시각화)

### Detection Engine (Processing)
- **Language**: Modern C++ (C++17/20)
- **Library**: librdkafka (Kafka 연동)
- **Pattern**: Sliding Window (Ring Buffer), SIMD 최적화

---

## 🏗 시스템 아키텍처

```text
[ 외부 주식 API ] 
      ↓ (WebSocket)
[ Spring Boot Ingestion ] ──→ [ Kafka (raw-stock-prices) ]
                                          ↓
                              [ C++ Detection Engine ] (Z-score Algorithm)
                                          ↓
[ Spring Boot Notifier ] ←── [ Kafka (stock-alerts) ]
      ↓ (SSE/WebSocket)
[ Next.js Dashboard ]
```

### 데이터 흐름
1. **수집**: Spring Boot 서버가 외부 API로부터 실시간 틱 데이터를 수집합니다.
2. **발행**: 수집된 데이터를 Kafka의 `raw-stock-prices` 토픽으로 발행합니다.
3. **탐지**: C++ 엔진이 데이터를 소비하여 링 버퍼 기반 슬라이딩 윈도우로 이상치를 계산합니다.
4. **알림**: 이상 탐지 시 `stock-alerts` 토픽으로 이벤트를 발행합니다.
5. **전달**: API 서버가 알림 이벤트를 소비하여 클라이언트에게 실시간(SSE)으로 전송합니다.
6. **저장**: 모든 틱 데이터는 사후 분석을 위해 InfluxDB에 기록됩니다.

---

## 📂 프로젝트 구조

```text
.
├── api-server/        # Spring Boot API 서버 및 데이터 인제스터
├── web-app/           # Next.js 기반 실시간 모니터링 웹 대시보드
├── processing-engine/ # C++ 기반 실시간 이상 탐지 엔진 (별도 구축 예정)
├── infra/             # Docker Compose (Kafka, MySQL, Redis, InfluxDB)
├── PROJECT_CONTEXT.md # 프로젝트 상세 기획 및 설계 의도
└── STRUCTURE.md       # 아키텍처 및 상세 디렉토리 구조 설명
```

---

## ⚙️ 시작하기

### 환경 요구 사항
- Docker & Docker Compose
- JDK 17
- Node.js 18+ & pnpm

### 1. 인프라 실행
```bash
cd infra
docker-compose up -d
```

### 2. 백엔드 서버 실행
```bash
cd api-server
./gradlew bootRun
```

### 3. 프론트엔드 실행
```bash
cd web-app
pnpm install
pnpm dev
```

---

## 📈 이상 탐지 알고리즘

현재 시스템은 다음과 같은 통계적 기법을 사용합니다:
- **Z-score**: 최근 N개 데이터의 평균과 표준편차를 기준으로 임계값을 벗어나는 데이터 탐지
- **EWMA (지수 가중 이동 평균)**: 최신 데이터에 더 높은 가중치를 두어 민감한 변화 탐지 (준비 중)
- **Welford 알고리즘**: 메모리 효율적인 온라인 분산 계산 수행

---

## 🛡 유지보수 원칙
- **Layered Architecture**: Domain, Application, Infrastructure, Web 계층의 엄격한 분리
- **Clean Code**: SOLID 원칙 준수 및 인터페이스 기반 설계
- **Testing**: JUnit5 및 Mockito를 활용한 핵심 비즈니스 로직 테스트
