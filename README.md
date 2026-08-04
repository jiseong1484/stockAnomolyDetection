# 실시간 주식 이상 탐지 시스템 (Stock Anomaly Detection System)

한국투자증권 실시간 시세를 Kafka로 스트리밍하고, 자체 이상탐지 엔진과 기술적 지표 기반 매매 시그널을 제공하는 실시간 주식 이상탐지 플랫폼입니다.

## 🚀 주요 특징

- **실시간 스트림 처리**: Kafka 기반 이벤트 드리븐 아키텍처로 4개 서비스(수집/탐지/스코어링/알림)를 느슨하게 결합
- **고성능 탐지 엔진**: C++ 워커가 락프리 큐와 오브젝트 풀로 힙 할당 없이 고빈도 틱을 처리
- **규칙 기반 매매 시그널**: 가격/거래량 지표를 역할 분리해 결합하는 Python 스코어링 서비스
- **시계열 데이터 최적화**: InfluxDB로 밀리초 단위 틱·캔들 데이터를 저장 및 분석
- **실시간 대시보드**: Next.js와 SSE(Server-Sent Events)로 지연 없는 모니터링 UI 제공

## 🛠 기술 스택

### API Server (`api-server`)
- **Framework**: Java 17, Spring Boot 3.2.4
- **Security**: Spring Security, JWT
- **Messaging**: Apache Kafka 3.7.0 (Spring Kafka)
- **Database**: MySQL 8.0 (사용자/구독), InfluxDB (틱·캔들 시계열), Redis (실시간가 캐시, JWT 블랙리스트)

### Trading Engine (`trading-engine`)
- **Language**: Modern C++ (C++20), CMake
- **Library**: librdkafka (Kafka 연동), nlohmann/json
- **Pattern**: Lock-free SPSC/MPSC 큐, 고정 크기 오브젝트 풀(Treiber-stack 방식 free list), Welford 기반 슬라이딩 윈도우 Z-score
- **Testing**: GoogleTest

### AI Signal Service (`ai-signal-service`)
- **Language**: Python
- **Library**: confluent-kafka, pandas, pyarrow, influxdb-client

### Web App (`web-app`)
- **Framework**: Next.js (App Router), React, TypeScript
- **Styling**: TailwindCSS, Shadcn/UI
- **Charts**: Recharts, SSE 기반 실시간 갱신

### Infra
- Docker Compose로 Kafka(KRaft 단일 브로커), MySQL, Redis, InfluxDB, 4개 서비스를 오케스트레이션

---

## 🏗 시스템 아키텍처

```text
[ 한국투자증권 WebSocket ]
        ↓
[ api-server: KisWebSocketClient ]
        ↓ publish (key = ticker)
[ Kafka: raw-market-data ]
        ├──→ [ trading-engine: TickAggregator ]
        │        ├─ 스캘핑 경로 → MPSC Queue → ZScoreCalculator(거래량, W=20)
        │        │        → [ Kafka: urgent-signals / anomaly-alerts ]
        │        └─ 스윙 경로 → 15m/1h 캔들 집계
        │                 → [ Kafka: candle-data ]
        └──→ [ api-server 자체 재소비 ] → InfluxDB 저장 + Redis 캐시 + SSE 브로드캐스트

[ Kafka: candle-data ]
        ├──→ [ api-server ] → InfluxDB OHLCV 저장
        └──→ [ ai-signal-service ]
                 - MACD / RSI / Bollinger %B / VWAP 이격도 / 거래량 Z-score 계산
                 - 가격 지표(가중합) × 거래량 지표(신뢰도 증폭)로 시그널 스코어링
                 - 계산된 피처를 Parquet으로 로깅 (향후 모델 학습용)
                 → [ Kafka: ai-signals ]

[ Kafka: ai-signals ]
        ├──→ [ trading-engine: SwingHandler ] (진입 상태 머신)
        └──→ [ api-server ] → SSE로 대시보드에 AI 시그널 전달

[ Kafka: anomaly-alerts ] → [ api-server ] → SSE 브로드캐스트

[ Next.js Dashboard ] ← SSE ← [ api-server ]
```

### 데이터 흐름 요약
1. **수집**: api-server가 한국투자증권 웹소켓으로 체결 데이터를 수신해 `raw-market-data`에 발행 (키 = 종목코드, 종목별 순서 보장)
2. **탐지**: trading-engine이 스캘핑 경로(거래량 Z-score + 가격 변화율)로 급변 이상치를 즉시 탐지하고, 스윙 경로에서는 캔들을 집계해 `candle-data`로 발행
3. **스코어링**: ai-signal-service가 캔들 기반 기술적 지표를 계산해 매매 시그널(`ai-signals`)을 산출하고, 향후 모델 학습을 위한 피처를 Parquet으로 남김
4. **알림**: trading-engine과 api-server가 각각 `ai-signals`/`anomaly-alerts`를 소비해 진입 판단 및 실시간 알림(SSE)을 수행
5. **저장**: 틱·캔들 데이터는 InfluxDB에, 사용자/구독 정보는 MySQL에 저장

---

## 📂 프로젝트 구조

```text
.
├── api-server/          # Spring Boot API 서버 — 데이터 수집, REST/SSE, 영속성
├── trading-engine/      # C++ 이상탐지 엔진 — 락프리 큐, 오브젝트 풀, Z-score 계산
├── ai-signal-service/   # Python 시그널 스코어링 서비스 — 기술적 지표, 피처 로깅
├── web-app/             # Next.js 실시간 모니터링 대시보드
├── infra/               # Docker Compose (Kafka, MySQL, Redis, InfluxDB) 및 배포 스크립트
├── PROJECT_CONTEXT.md   # 프로젝트 상세 기획 및 설계 의도
└── STRUCTURE.md         # 아키텍처 및 상세 디렉토리 구조 설명
```

---

## ⚙️ 시작하기

### 환경 요구 사항
- Docker & Docker Compose
- JDK 17, Node.js 18+ & pnpm (로컬 개발 시)

### 인프라 및 전체 서비스 실행
```bash
cd infra
docker compose up -d --build
```

### 개별 서비스 로컬 실행 (개발 중)
```bash
# API 서버
cd api-server && ./gradlew bootRun

# 프론트엔드
cd web-app && pnpm install && pnpm dev
```

---

## 📈 탐지·시그널링 알고리즘

### trading-engine — 거래량 기반 Z-score 이상탐지
- **Welford 알고리즘 기반 슬라이딩 윈도우**: 고정 크기 윈도우(W=20)에서 틱마다 evict-insert를 결합한 O(1) 갱신으로 평균/분산을 유지 (매 틱 전체 윈도우 재계산 없음)
- 거래량 Z-score와 가격 변화율(윈도우 내 % 변화)을 함께 게이트로 사용해 스캘핑 이상 시그널을 발화
- 스윙 경로는 별도로 15분/1시간 캔들을 집계해 `candle-data`로 발행

### ai-signal-service — 가격/거래량 역할 분리 스코어링
- 가격 지표(MACD, RSI, Bollinger %B, VWAP 이격도)를 각각 [-1, 1]로 정규화 후 가중합해 방향성 점수 산출
- 거래량 Z-score는 방향에 투표하지 않고, 방향성 점수의 신뢰도를 증폭하는 보정치로만 사용
- 최종 점수를 시그모이드에 통과시켜 매수/매도 확률로 환산, 임계값 기준으로 `BUY_READY` / `SELL_READY` / `NEUTRAL` 판정
- 판단에 사용된 모든 피처는 Parquet으로 로깅 — 현재는 규칙 기반 스코어만 기록하며, 라벨링·모델 학습 파이프라인은 향후 과제

---

## ⚡ 동시성 및 성능 설계 (trading-engine)

- **Lock-free Queue**: SPSC/MPSC 링버퍼로 스캘핑 처리 경로의 틱 인입을 처리, 캐시라인 정렬로 false sharing 방지
- **Object Pool**: 종목별 상태(`SwingTickerState`)와 Kafka 발행 버퍼를 CAS 기반 고정 크기 풀에서 재사용해 고빈도 처리 구간의 힙 할당을 제거
- **경로별 동시성 전략 분리**: 초저지연이 중요한 스캘핑 경로는 락프리로, 상대적으로 빈도가 낮은 스윙/캔들 집계 경로는 mutex 기반으로 설계해 복잡도와 성능을 절충

---

## 🛡 유지보수 원칙

- **Layered Architecture**: Domain, Application, Infrastructure, Web 계층 분리 (api-server)
- **Testing**: JUnit5/Mockito(api-server), GoogleTest(trading-engine)로 핵심 로직 검증

---

## 🔭 향후 개선 방향

- Kafka 멀티 파티션 + 탐지 엔진 다중 인스턴스로 실제 수평 확장 검증
- Kafka 브로커 다중화(현재 단일 브로커, replication factor 1)
- 주문 실행 연동 (현재 브로커 주문 API는 스텁 상태)
- 피처 로깅 기반 라벨링 및 모델 학습 파이프라인 구축
- Prometheus + Grafana 기반 모니터링, 클라우드 환경 배포
