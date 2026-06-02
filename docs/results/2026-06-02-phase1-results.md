# Phase 1 벤치마크 결과 분석 (2026-06-02)

## 1. 실행 환경

| 항목 | 내용 |
|---|---|
| OS | macOS (Darwin 24.6.0) |
| DB | Docker — mysql:8.0 (localhost:3306) |
| Cache | Docker — redis:6-alpine (localhost:6379) |
| Java | 17.0.18 |
| Gradle | 8.12 |
| Spring Boot | 3.4.1 |
| clients | 200 |
| warmupRounds | 2 (BenchConfig 기본값) |
| measuredRounds | 5 (BenchConfig 기본값) |
| POOL_SIZE (전체 행렬) | 50 |

---

## 2. 전체 결과표 (3×3 행렬, POOL_SIZE=50, clients=200)

| strategy | scenario | throughput/s | p50(µs) | p95(µs) | p99(µs) | success | soldOut | errors | oversell | conserved |
|---|---|---|---|---|---|---|---|---|---|---|
| mysql-skip-locked | HOT_SINGLE | 2194.4 | 11415 | 35229 | 59355 | 1000 | 0 | 0 | 0 | true |
| mysql-skip-locked | LAST_UNITS | 76.1 | 5700 | 18007 | 24579 | 10 | 990 | 0 | 0 | true |
| mysql-skip-locked | MIXED | 2119.0 | 11682 | 34978 | 65601 | 1000 | 0 | 0 | 0 | true |
| redis-counter | HOT_SINGLE | 33560.7 | 872 | 1324 | 1616 | 1000 | 0 | 0 | 0 | true |
| redis-counter | LAST_UNITS | 639.1 | 768 | 1240 | 1395 | 10 | 990 | 0 | 0 | true |
| redis-counter | MIXED | 21088.9 | 1347 | 2016 | 2595 | 1000 | 0 | 0 | 0 | true |
| redis-ledger-split | HOT_SINGLE | 7879.4 | 5256 | 6159 | 6626 | 1000 | 0 | 0 | 0 | true |
| redis-ledger-split | LAST_UNITS | 484.3 | 3930 | 5338 | 6085 | 50 | 950 | 0 | **40** | **false** |
| redis-ledger-split | MIXED | 4634.9 | 4636 | 7160 | 7908 | 1000 | 0 | 0 | 0 | true |

---

## 3. 3-way 정합성·성능 분석

### ① MySQL SKIP LOCKED — ACID 보장, 완벽한 재고 보존

- **oversell: 전 시나리오 0**, conserved: 전 시나리오 true
- HOT_SINGLE 처리량 **2,194 req/s**, p99 **59ms**
- LAST_UNITS 처리량이 **76 req/s**로 급감: 재고 10개가 소진되면 나머지 990개 요청이 `SKIP LOCKED`에 의해 건너뛰어지고 sold-out 처리됨. 실제 충돌 없이 순차 소모 → 처리 단건당 비용이 커짐
- MIXED 시나리오에서도 **2,119 req/s**로 HOT_SINGLE과 거의 동일 — SKU 분산이 락 경합을 줄여줌
- 결론: DB 트랜잭션이 원자적으로 재고를 잠그므로 어떤 시나리오에서도 oversell이 없음. 처리량은 Redis에 비해 낮지만 정합성은 가장 확실

### ② 순수 Redis (redis-counter) — 최고 처리량, 단일 시스템 정합성 OK

- **oversell: 전 시나리오 0**, conserved: 전 시나리오 true
- HOT_SINGLE 처리량 **33,561 req/s** (MySQL 대비 **15.3×**), p99 **1.6ms**
- MIXED 시나리오에서도 **21,089 req/s** — Redis DECR 원자 연산 덕분에 SKU 다양성과 무관하게 높은 처리량 유지
- LAST_UNITS: **639 req/s** — HOT_SINGLE 대비 하락하지만 p99가 1.4ms로 MySQL(24ms)보다 훨씬 낮음
- 결론: Redis 단일 데이터스토어에서 DECR/WATCH 방식은 처리량과 정합성을 동시에 달성. 단, Redis가 영속성 없이 운영될 경우 장애 시 재고 소실 위험이 별도로 존재

### ③ Redis+MySQL 분리 (redis-ledger-split) — Atomicity Gap으로 oversell 발생

- HOT_SINGLE, MIXED: oversell 0, conserved true
- **LAST_UNITS: oversell = 40**, conserved = **false** ← 핵심 관찰
- 처리량 **7,879 req/s** (HOT_SINGLE) — 순수 Redis보다 낮고, MySQL보다 높음
- Atomicity Gap 메커니즘: Redis에서 재고를 차감한 뒤 MySQL 레저에 기록하기까지의 짧은 시간 창에서 다수 코루틴이 동시에 Redis 잔량을 읽어 `> 0`으로 판단하고 차감을 진행함. 재고가 10개뿐인 LAST_UNITS에서는 이 경쟁이 가시적인 40건 oversell로 나타남
- HOT_SINGLE/MIXED에서 oversell이 없는 이유: 재고(1000개)가 충분해 경쟁 윈도우 안에 0 이하로 내려가지 않음
- 결론: Redis와 MySQL을 별개로 운영하면 양쪽을 동시에 원자적으로 갱신할 수 없다. 재고 잔량이 극히 적을 때(=실제 판매 막바지) 정합성이 깨짐 — 가장 중요한 순간에 실패

### 처리량 비교 요약

```
redis-counter   > redis-ledger-split > mysql-skip-locked
  33,561/s           7,879/s              2,194/s
  (정합성 OK)      (LAST_UNITS 취약)     (정합성 완벽)
```

---

## 4. 커넥션 풀 관찰 (mysql-skip-locked / HOT_SINGLE, clients=200)

| POOL_SIZE | throughput/s | p50(µs) | p95(µs) | p99(µs) | conserved |
|---|---|---|---|---|---|
| 5 | **881.9** | 33,986 | 181,259 | 208,567 | true |
| 50 | **2,204.2** | 11,234 | 39,422 | 58,620 | true |

**관찰**: pool=5 → pool=50으로 증가 시 처리량이 **2.5×** (881.9 → 2,204.2) 개선되고, p99가 **208ms → 58ms**로 대폭 단축됨.

**해석**: 쿼리 자체의 실행 시간(MySQL SKIP LOCKED 단건)은 수 ms 수준이다. 그럼에도 pool=5일 때 p99가 208ms에 달하는 것은, 200개 코루틴이 커넥션 5개를 두고 경쟁하며 HikariCP 대기 큐에서 대부분의 시간을 소비하기 때문이다. **쿼리가 아니라 커넥션 점유가 병목** — Shopify 엔지니어링 블로그("Thinking About the Solution Space", SKIP LOCKED 편)에서 지적한 핵심 통찰과 일치한다.

> "The query is fast; it's the wait for a connection that kills you."

pool=50으로 충분한 커넥션을 확보하면 대기 없이 즉시 실행되어 처리량이 회복된다. 단, Dispatchers.IO 기본 스레드 풀(64개)이 유효 동시성의 상한선이므로 pool을 64 이상으로 늘려도 추가 효과가 제한될 수 있다.

---

## 5. 한계 및 주의사항

1. **로컬 단일 머신**: MySQL, Redis, 애플리케이션이 모두 같은 호스트에서 실행되어 네트워크 레이턴시가 실제 운영 환경보다 훨씬 낮다. 절대 수치보다 전략 간 **상대 비교**가 유효하다.
2. **replenishment 미포함**: 재고 보충(재입고) 로직은 Phase 2 범위. 현재 벤치마크는 소진(감소)만 측정한다.
3. **Dispatchers.IO 64스레드 상한**: Kotlin 코루틴의 기본 IO 디스패처는 최대 64개 스레드를 사용한다. clients=200이지만 실질적으로 64개 초과 동시 실행이 되지 않아, 이론적 최대 병렬도에 도달하지 못할 수 있다.
4. **워밍업 2라운드**: JIT 컴파일이 측정 구간에 영향을 줄 수 있다. warmupRounds를 늘리면 더 안정적인 수치를 얻을 수 있다.
5. **Docker 오버헤드**: Docker Desktop(macOS) 위에서 실행되는 MySQL/Redis는 Linux 네이티브 대비 I/O 성능이 낮다.
