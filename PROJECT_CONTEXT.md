📌 프로젝트 컨텍스트: 실시간 주식 이상 탐지 시스템

1. 프로젝트 개요

이 프로젝트는 실시간 금융 데이터를 스트리밍으로 처리하여 이상 변동성을 탐지하는 시스템이다.
	•	목표: 밀리초 단위의 지연으로 급격한 주가 변동(이상치)을 탐지
	•	구조: Kafka 기반 이벤트 드리븐 마이크로서비스 아키텍처
	•	핵심 특징: 고처리량(High-throughput) + 저지연(Low-latency) + C++ 기반 성능 최적화

⸻

2. 주요 구성 요소

1) Ingestion & API 서버 (Spring Boot)
	•	외부 주식 API(WebSocket)로부터 실시간 틱 데이터 수집
	•	원본 데이터를 Kafka로 발행
	•	클라이언트에게 SSE/WebSocket으로 알림 전송

⸻

2) 메시지 브로커 (Kafka)
	•	서비스 간 비동기 이벤트 스트리밍 담당
	•	주요 토픽:
	•	raw-stock-prices
	•	stock-alerts
	•	종목 코드(Ticker)를 Key로 사용하여 파티션 분배 (순서 보장)

⸻

3) 핵심 처리 엔진 (C++)
	•	Kafka Consumer 기반 워커
	•	실시간 이상 탐지 연산 수행 (CPU-bound 작업)

주요 특징:
	•	Ring Buffer 기반 슬라이딩 윈도우
	•	커스텀 메모리 풀 (Memory Pool)
	•	Lock-free 자료구조
	•	SIMD 최적화 (선택적)

⸻

4) 저장소 계층
	•	Redis
	•	사용자 임계값 캐싱
	•	실시간 변동성 랭킹 (ZSET)
	•	InfluxDB
	•	밀리초 단위 시계열 데이터 저장
	•	MySQL
	•	사용자 정보, 알림 기록 등 트랜잭션 데이터 저장

⸻

3. 데이터 흐름
	1.	Spring Boot 서버가 실시간 주식 데이터를 수집
	2.	Kafka (raw-stock-prices)로 원본 데이터 발행
	3.	C++ 엔진이 데이터를 Consume하여 처리
	4.	이상치 탐지 수행
	5.	Kafka (stock-alerts)로 알림 이벤트 발행
	6.	API 서버가 이를 소비하여 클라이언트에 전달
	7.	시계열 데이터는 InfluxDB에 저장

⸻

4. 핵심 알고리즘

슬라이딩 윈도우
	•	최근 N개의 틱 데이터를 Ring Buffer로 유지

이상 탐지 방식
	•	기본: Z-score 기반 이상치 탐지
	•	향후 개선:
	•	EWMA (지수 가중 이동 평균)
	•	Welford 알고리즘 (온라인 분산 계산)
	•	MAD 기반 강건 통계

⸻

5. 주요 설계 의도

Kafka를 사용한 이유
	•	대용량 데이터 스트리밍 처리에 적합
	•	서비스 간 결합도 감소 (Decoupling)
	•	장애 발생 시 데이터 유실 방지

⸻

C++를 사용한 이유
	•	GC가 없어 예측 가능한 지연 시간
	•	메모리 및 CPU 최적화 가능
	•	실시간 연산 성능 극대화

⸻

시계열 DB(InfluxDB)를 사용한 이유
	•	시간 기반 데이터 조회에 최적화
	•	고빈도 데이터 저장 및 분석에 유리

⸻

6. 성능 고려 사항
	•	“Zero-lag”가 아닌 Sub-second latency 목표
	•	이벤트마다 동적 메모리 할당 방지
	•	Redis 접근 최소화 (로컬 캐싱 고려)
	•	Kafka 파티션 기반 병렬 처리

⸻

7. 확장성 전략
	•	Kafka 파티션 기반 수평 확장
	•	C++ 워커 노드 확장
	•	Stateless 구조 유지

⸻

8. 향후 개선 방향
	•	Z-score → 더 강건한 이상 탐지 알고리즘으로 개선
	•	Kafka lag 기반 Backpressure 처리
	•	Prometheus + Grafana 기반 모니터링
	•	클라우드 환경 배포

⸻

9. AI를 위한 참고 사항
	•	본 시스템은 배치 처리(Batch)가 아닌 스트림 처리(Stream Processing) 시스템이다
	•	실시간성이 매우 중요한 시스템이다
	•	C++ 코드는 성능 최적화가 핵심이다
	•	처리 파이프라인에서 블로킹 연산은 지양해야 한다
:::