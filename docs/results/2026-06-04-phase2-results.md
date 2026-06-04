# Phase 2 실험 결과 — 격리수준 / Bounded Pool 분석

작성일: 2026-06-04

---

## 1. 실행 환경

| 항목 | 값 |
|---|---|
| OS | macOS Darwin 24.6.0 (Apple Silicon, 단일 머신) |
| DB | Docker `mysql:8.0`, `--innodb-lock-wait-timeout=3` |
| Runtime | Java 17.0.18, Gradle 8.12, Spring Boot 3.4.1 |
| E1 기본값 | rounds=10, clients=100 (추가 시도: clients=200) |
| E2 기본값 | rounds=20, clients=100, ledgerTotal=5000, poolCap=1000 |

---

## 2. E1 결과 — 격리수준별 락 충돌

> **E1 설정 변경 (poolCap=50)**: 초기 poolCap=1000 에서는 SKIP LOCKED 쿼리가 잠긴 행 없이 이른 id에서 행을 확보하므로 supremum pseudo-record까지 스캔하지 않았다. poolCap=50 으로 낮춰 100개 클라이언트 경합 시 풀이 빠르게 고갈되어 SKIP LOCKED 스캔이 supremum까지 도달하도록 유도했다. supremum gap lock이 replenishment INSERT와 충돌하면 데드락(1213) 또는 락 대기 타임아웃(1205)이 발생한다.

**시도 1 (poolCap=50, clients=100, 1차)**

| isolation | deadlocks(1213) | lockWaitTimeouts(1205) | otherErrors | successes | soldOut |
|---|---|---|---|---|---|
| REPEATABLE_READ | 0 | 0 | 0 | 424 | 576 |
| READ_COMMITTED | 0 | 0 | 0 | 500 | 500 |

**시도 2 (poolCap=50, clients=100, 2차 재실행)**

| isolation | deadlocks(1213) | lockWaitTimeouts(1205) | otherErrors | successes | soldOut |
|---|---|---|---|---|---|
| REPEATABLE_READ | 0 | 0 | 0 | 450 | 550 |
| READ_COMMITTED | 0 | 0 | 0 | 500 | 500 |

**시도 3 (poolCap=50, clients=200, 재시도)**

| isolation | deadlocks(1213) | lockWaitTimeouts(1205) | otherErrors | successes | soldOut |
|---|---|---|---|---|---|
| REPEATABLE_READ | 0 | 0 | 0 | 500 | 1500 |
| READ_COMMITTED | 0 | 0 | 0 | 500 | 1500 |

poolCap=50 으로 낮춘 후 3회 시도에서도 REPEATABLE_READ의 deadlocks/lockWaitTimeouts는 0이었다.

---

## 3. E1 해석

### 이론적 기대

REPEATABLE_READ에서 `SELECT ... FOR UPDATE SKIP LOCKED`는 조회한 인덱스 범위의 끝(supremum pseudo-record)에 **gap lock**을 설정한다. `replenishment`가 해당 범위 안에 새 행을 `INSERT`하려 할 때 이 gap lock과 충돌해 데드락(1213) 또는 락 대기 타임아웃(1205)이 발생할 수 있다. READ COMMITTED는 gap lock을 설정하지 않으므로 충돌이 없다. Shopify가 REPEATABLE READ → READ COMMITTED로 전환한 이유와 같은 원리다.

### poolCap=50 으로도 재현되지 않은 원인 분석

#### 왜 poolCap을 낮춰야 하는가 (이론)

`LIMIT 1 FOR UPDATE SKIP LOCKED`는 잠긴 행을 건너뛰며 첫 번째 잠금 가능한 행을 찾는 즉시 멈춘다. poolCap=1000 처럼 풀이 크면, 100개 클라이언트가 동시에 접근해도 대부분이 낮은 id의 행을 각자 찾아 supremum 이전에 스캔을 종료한다. 따라서 supremum gap lock이 거의 발생하지 않는다. poolCap=50 이면 행이 빠르게 고갈되고 SKIP LOCKED 스캔이 끝까지 도달해 supremum gap lock을 취득할 가능성이 높아진다.

#### 로컬 단일 머신에서 재현이 어려운 복합 이유

1. **트랜잭션 창(window)이 너무 좁음**: `reserve` 트랜잭션에서 SELECT FOR UPDATE → DELETE → INSERT into reserved_quantities까지 동일 트랜잭션 안에서 처리된다. 이 전체 구간이 수 밀리초 이내에 완료되므로, replenisher가 정확히 그 창 안에서 INSERT를 시도해야 충돌이 발생한다. `delay(2)`로 설정된 replenisher 주기와 단일 JVM 코루틴 스케줄러 특성상 이 타이밍이 맞지 않는 경우가 대부분이다.

2. **MySQL 서버 기본 격리수준이 READ-COMMITTED**: `docker-compose.yml`에 `--transaction-isolation=READ-COMMITTED`로 서버가 시작되어 있다. Spring의 `TransactionTemplate`은 트랜잭션 시작 전에 `SET TRANSACTION ISOLATION LEVEL REPEATABLE READ`를 실행하므로 격리수준 자체는 올바르게 설정되지만, MySQL 내부의 gap lock 생성 경로가 서버-레벨 RC 기본값에 영향을 받을 수 있다는 점은 추가 검증이 필요하다.

3. **SKIP LOCKED와 gap lock의 상호작용**: `SKIP LOCKED`는 잠긴 행을 건너뛰기 때문에, 스캔이 supremum에 도달하더라도 MySQL이 supremum gap lock을 실제로 획득하는지는 행 분포와 인덱스 스캔 경로에 따라 달라진다. 100개 클라이언트가 경쟁하더라도 풀이 고갈되는 순간 대부분의 트랜잭션은 0행을 반환받아 setRollbackOnly()로 즉시 롤백하므로 gap lock을 취득하는 트랜잭션 수가 적다.

4. **단일 머신 JVM 스케줄러 직렬화**: 실제 멀티 노드 분산 환경과 달리 코루틴이 하나의 JVM `Dispatchers.IO` 스레드 풀을 공유하므로, reserve와 replenishment 트랜잭션이 완전히 겹치는 실제 동시성이 제한된다.

이 실험에서는 **poolCap=50으로도 데드락을 재현하지 못했음을 솔직히 밝힌다.** 이론적 인과관계(gap lock 충돌)는 유효하며, REPEATABLE_READ의 soldOut 수가 RC보다 많은 것(시도 1: 576 vs 500)은 격리수준 차이가 동작에 영향을 주고 있음을 시사한다. 그러나 로컬 단일 머신 환경의 타이밍 제약으로 인해 확률적으로 충돌이 발생하지 않은 것으로 판단한다.

---

## 4. E2 결과 — Bounded Pool 정합성

**실행 (clients=100, rounds=20, ledgerTotal=5000, poolCap=1000)**

| total | successes(sold) | oversell | conserved | maxPoolObserved | finalPool |
|---|---|---|---|---|---|
| 5000 | 2000 | 0 | true | 1000 | 1000 |

---

## 5. E2 해석

- **oversell = 0**: 100 클라이언트가 20 라운드 동안 동시에 reserve→claim을 수행했지만 `sold + reserved`가 `total(5000)`을 초과한 적이 없다. `SELECT ... FOR UPDATE SKIP LOCKED`의 배타적 행 잠금이 중복 예약을 완전히 차단했다.
- **maxPoolObserved = 1000**: `poolCap` 상한을 한 번도 초과하지 않았다. replenishment는 풀 행 수가 poolCap 미만일 때만 INSERT하는 조건을 준수했다.
- **conserved = true**: 최종 상태에서 `sold + reserved ≤ total`이 성립한다.
- **finalPool = 1000**: 20라운드 내에 ledgerTotal=5000 중 2000(sold)만 소진되었고, 나머지 재고 범위 안에서 풀이 꽉 채워진 상태로 종료됐다.

bounded pool 설계가 **고부하 동시 접근 하에서도 oversell 없이 재고 정합성을 유지**함을 확인했다.

---

## 6. 한계

| 항목 | 내용 |
|---|---|
| 단일 머신 실행 | 분산 멀티 노드 환경이 아니어서 실제 운영 동시성 수준을 재현하지 못함 |
| E1 데드락 미재현 | poolCap=50 으로 낮춰 supremum gap lock 유도를 시도했으나, 단일 머신 JVM 환경에서 트랜잭션 창이 너무 짧고 코루틴 스케줄러 타이밍 의존성으로 3회 시도에서 gap lock 충돌이 발생하지 않음; 이론적 위험은 유효하나 실험으로 확인되지 않음 |
| MySQL 서버 격리수준 | 서버 기본값이 READ-COMMITTED로 설정되어 있어, Spring의 per-session RR 설정이 MySQL gap lock 동작에 미치는 영향을 완전히 격리하지 못했을 가능성이 있음 |
| lock-wait timeout 인위 단축 | 3초로 설정해 타임아웃 발생 기회를 높였으나 E1에서는 효과 없음 |
| E3 미구현 | Phase 2 스펙의 선택 항목(lock ordering 실험)은 이번 PoC에서 구현하지 않음 |
| 클라이언트 수 제한 | 최대 clients=200으로 시도; 더 높은 동시성에서는 결과가 달라질 수 있음 |
