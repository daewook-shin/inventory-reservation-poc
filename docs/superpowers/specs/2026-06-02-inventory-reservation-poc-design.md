# 재고 예약(Inventory Reservation) 3-way 비교 PoC — 설계

> 작성일: 2026-06-02
> 영감: [Shopify — Scaling Inventory Reservations](https://shopify.engineering/scaling-inventory-reservations) / [GeekNews 토픽](https://news.hada.io/topic?id=30006)

## 1. 목적

체크아웃 재고 예약을 구현하는 세 가지 방식을 **동일 조건에서 비교**하여 정합성·처리량·트레이드오프를 직접 측정한다.

검증하려는 5가지:
1. **동시성 정합성** — 동시 예약 시 oversell/undersell이 안 일어나는가
2. **처리량/경합** — 핫 아이템 부하에서 SKIP LOCKED가 락 경합을 줄여 throughput을 올리는가
3. **데이터 모델 체감** — one-row-per-unit + bounded pool 구조의 동작 이해
4. **전체 흐름 재현** — reserve → claim → release 라이프사이클
5. **Redis 비교 분석** — 순수 Redis(성능 상한)와 Redis+MySQL 분리(정합성 결함)의 장단점

## 2. 비교 대상 전략 (3-way)

| | 전략 | 핵심 | 기대 결과 |
|---|---|---|---|
| ① | `MySqlSkipLockedStrategy` | one-row-per-unit + `FOR UPDATE SKIP LOCKED` | oversell 0, 처리량 중상, ACID |
| ② | `RedisCounterStrategy` | Lua 원자 DECR 카운터 | 처리량 **최고**, 자기 시스템 내 정합성 OK |
| ③ | `RedisLedgerSplitStrategy` | Redis 예약 + MySQL 원장(별도 트랜잭션) | **oversell 발생**(atomicity gap) → "왜 바꿨나" |

> **결과 해석 주의**: ②의 oversell=0은 *정상이자 의도된 결과*다. Redis 단일 시스템은 원자 카운터로 일관성을 유지한다. 정합성 문제는 ③처럼 원장이 분리될 때 발생한다. 숫자를 "Redis가 더 안전하다"로 오독하지 않는다.

## 3. 아키텍처 (접근법 A — 단일 앱 + Strategy 플러그인)

```
benchmark-runner (코루틴 부하 생성기)
        │  동일 시나리오 N개 클라이언트 동시 실행
        ▼
ReservationStrategy (인터페이스)
   ├── MySqlSkipLockedStrategy
   ├── RedisCounterStrategy
   └── RedisLedgerSplitStrategy
        │
        ▼
CorrectnessOracle (정합성 검증) + MetricsCollector (성능 집계)
```

- 단일 Spring Boot(Kotlin) 앱. `--strategy=` 인자로 한 번에 하나씩 실행.
- 부하는 **HTTP 미경유, 서비스 계층 직접 호출** — 네트워크 노이즈를 빼고 예약 메커니즘(DB 락 vs Redis)만 측정.
- 결과 JSON을 누적해 마지막에 비교표 출력.

## 4. 인터페이스

```kotlin
interface ReservationStrategy {
    fun setup(itemId: Long, locationId: Long, totalStock: Int)
    fun reserve(itemId: Long, locationId: Long, qty: Int): ReservationResult  // SUCCESS / SOLD_OUT
    fun claim(reservationId: String)        // 결제 확정 → 원장 차감
    fun release(reservationId: String)      // 취소 → 재고 복귀 (수동 호출만, TTL 스위퍼 없음)
    fun snapshot(): InventorySnapshot       // oracle용: sold, reserved, available
    fun reset()                             // 전략/시나리오 전환 시 상태 초기화
    fun name(): String
}
```

## 5. 데이터 모델 (전략 ①·③)

- **`reservation_units`** — *단위당 1행*. 복합 PK `(shop_id, item_id, location_id, id)`. 가용 풀 상한 1,000행.
- **`reserved_quantities`** — 예약 확정분. reserve가 INSERT, claim/release가 정리.
- **`inventory_ledger`** — 권위 있는 재고 원장 (claim의 source of truth).
- 예약 쿼리: `SELECT ... FOR UPDATE SKIP LOCKED LIMIT :qty`
- lock ordering 고정: units DELETE → reserved INSERT
- 격리수준: `READ COMMITTED`
- **Phase 1 제약**: `totalStock ≤ 1000`으로 세팅하여 한 런 안에서 replenishment가 트리거되지 않게 한다. (보충 잡은 Phase 2)

전략 ② Redis 키: `stock:{item}:{loc}` 카운터 + `reservations` 해시.
전략 ③: Redis 예약 카운트 + MySQL 원장을 **별도 트랜잭션**으로 차감 → atomicity gap을 의도적으로 노출.

## 6. 부하 하니스 & 정합성 오라클

- **부하**: `coroutineScope`로 동시 클라이언트 C개(설정값, 기본 200) × 라운드 R회.
- **워밍업 분리(필수)**: 첫 N라운드(설정값)는 측정에서 제외 — JVM JIT 왜곡 방지.
- **시나리오 3종**:
  - `HOT_SINGLE` — 모두가 1개 핫 아이템에 몰림 (락 경합 극대화, 처리량 측정)
  - `LAST_UNITS` — 재고 10개에 1,000명 (oversell 검증의 핵심)
  - `MIXED` — 1,000개 아이템에 분산 (현실적 처리량)
- **정합성 오라클** (각 라운드 후 단언):
  - `sold + reserved + available == totalStock` (보존 법칙)
  - 같은 unit이 두 번 예약되지 않음 → oversell = 0 확인
  - 성공 예약 수 ≤ 실제 재고 → oversell/undersell 카운트
- **메트릭**: throughput(reserve/s), p50/p95/p99 latency(`nanoTime` 기반), oversell 수, undersell 수, 실패율.

## 7. 커넥션 풀 노브 (글의 핵심 통찰 재현)

- HikariCP `maximumPoolSize`를 **명시적 설정값**으로 노출.
- 풀 대기시간(connection acquisition time)을 측정·로깅.
- 풀이 마르는 시나리오를 통해 *"쿼리는 빠른데 throughput이 안 나오는"* 현상을 재현 — Shopify가 발견한 "병목은 쿼리가 아니라 커넥션 점유" 통찰.

## 8. 인프라 & 산출물

- **Docker Compose**: `mysql:8.0`(`public.ecr.aws/docker/library/mysql:8.0`, 보유) + `redis:6-alpine`(보유). 추가 다운로드 0.
  - MySQL 8.0.1+ 이므로 `FOR UPDATE SKIP LOCKED` 지원 확인됨.
- **Flyway** 마이그레이션으로 스키마 관리.
- 빌드: Gradle Kotlin DSL.
- 최종 산출물: 콘솔 비교표 + `docs/` 결과 마크다운(세 전략 × 세 시나리오의 정합성·성능 매트릭스 + 장단점 분석).

## 9. 단계 구성

### Phase 1 (핵심 — 본 PoC의 완결 범위)
전략 3종 + 부하 하니스 + 오라클 + 3 시나리오 + 워밍업 분리 + 커넥션 풀 노브 + 상태 리셋.
→ 목적의 5가지 검증 항목을 모두 충족.

### Phase 2 (선택 — 스트레치)
Replenishment 잡 추가 → READ COMMITTED / gap-lock(supremum) / lock-ordering 데드락 이슈를 직접 재현.

## 10. 스코프 제외(YAGNI)

- TTL 기반 예약 만료 자동 스위퍼 (수동 release로 충분)
- HTTP API 레이어 (in-process 호출로 충분)
- 멀티 로케이션 분산 시나리오 (단일 location으로 핵심 검증 가능; 모델은 location_id 유지)
- 인증/멀티테넌시 (shop_id 컬럼은 유지하되 단일 shop)

## 11. GitHub

- 레포: `daewook-shin/inventory-reservation-poc` (**Public**)
- 프로토콜: SSH (gh 인증 완료)
- 레포 생성/최초 푸시는 실제 실행 직전 사용자에게 재확인 후 진행.
